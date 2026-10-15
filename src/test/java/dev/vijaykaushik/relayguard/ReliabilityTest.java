package dev.vijaykaushik.relayguard;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:relayguard-test;DB_CLOSE_DELAY=-1", "relayguard.worker-enabled=false", "relayguard.webhook-secret=test-secret"})
@AutoConfigureMockMvc
class ReliabilityTest {
    @Autowired EventService events;
    @Autowired DeliveryWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM delivery_attempt"); jdbc.update("DELETE FROM demo_ledger"); jdbc.update("DELETE FROM webhook_event");
    }
    EventService.Receipt receive(String tenant,int failures) { return events.accept(tenant,"evt_1",4900,"USD","same raw payload",failures); }
    Instant now() { return Instant.now().plusSeconds(1); }

    @Test void duplicatesReturnSameReceipt() {
        var first=receive("acme",0); var second=receive("acme",0);
        assertThat(second.duplicate()).isTrue(); assertThat(second.id()).isEqualTo(first.id());
        assertThat(events.metrics().get("received")).isEqualTo(1L);
    }
    @Test void sameKeyWithDifferentBodyIsConflict() {
        receive("acme",0);
        assertThatThrownBy(()->events.accept("acme","evt_1",5000,"USD","changed payload",0))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void idempotencyIsScopedToTenant() {
        var a=receive("acme",0); var b=receive("orbit",0);
        worker.process(a.id(),now()); worker.process(b.id(),now());
        assertThat(events.metrics().get("ledgerEntries")).isEqualTo(2L);
    }
    @Test void concurrentDeliveryHasOneLedgerSideEffect() throws Exception {
        var id=receive("acme",0).id(); var pool=Executors.newFixedThreadPool(8);
        try {
            var calls=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<16;i++) calls.add(()->worker.process(id,now()));
            var results=pool.invokeAll(calls); int claimed=0;
            for(var result:results) if(result.get()) claimed++;
            assertThat(claimed).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(events.metrics().get("ledgerEntries")).isEqualTo(1L);
        assertThat(events.attempts(id)).hasSize(1);
    }
    @Test void concurrentReceiptsCollapseDuplicates() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var calls=new ArrayList<Callable<String>>();
            for(int i=0;i<16;i++) calls.add(()->receive("acme",0).id());
            var results=pool.invokeAll(calls); var ids=new java.util.HashSet<String>();
            for(var result:results) ids.add(result.get());
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
        assertThat(events.metrics().get("received")).isEqualTo(1L);
    }
    @Test void retriesRespectBackoffAndEventuallyRecover() {
        var id=receive("acme",2).id(); Instant t=now();
        assertThat(worker.process(id,t)).isTrue();
        assertThat(worker.process(id,t.plusSeconds(1))).isFalse();
        assertThat(worker.process(id,t.plusSeconds(2))).isTrue();
        assertThat(worker.process(id,t.plusSeconds(5))).isFalse();
        assertThat(worker.process(id,t.plusSeconds(6))).isTrue();
        assertThat(events.metrics().get("delivered")).isEqualTo(1L);
        assertThat(events.metrics().get("ledgerEntries")).isEqualTo(1L);
        assertThat(events.attempts(id)).hasSize(3);
    }
    @Test void exhaustedEventsCanBeReplayedWithHistoryRetained() {
        var id=receive("acme",99).id(); Instant t=now();
        worker.process(id,t); worker.process(id,t.plusSeconds(2)); worker.process(id,t.plusSeconds(6));
        assertThat(events.metrics().get("dead")).isEqualTo(1L);
        assertThat(events.metrics().get("ledgerEntries")).isEqualTo(0L);
        events.replay(id); worker.process(id,t.plusSeconds(7));
        assertThat(events.metrics().get("delivered")).isEqualTo(1L);
        assertThat(events.attempts(id)).hasSize(4);
    }
    @Test void deliveredEventCannotBeReplayed() {
        var id=receive("acme",0).id(); worker.process(id,now());
        assertThatThrownBy(()->events.replay(id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }
    @Test void missingEventIsNotFound() {
        assertThatThrownBy(()->events.attempts("missing")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
    @Test void signatureMustMatchExactPayload() throws Exception {
        String body="{\"eventId\":\"signed\",\"amountMinor\":100,\"currency\":\"USD\"}";
        http.perform(post("/api/webhooks/acme").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        http.perform(post("/api/webhooks/acme").header("X-Relay-Signature",sign(body)).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(false));
        http.perform(post("/api/webhooks/acme").header("X-Relay-Signature",sign(body)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        http.perform(post("/api/webhooks/acme").header("X-Relay-Signature",sign(body)).contentType("application/json").content(body+" "))
                .andExpect(status().isUnauthorized());
    }
    @Test void invalidPaymentDoesNotReachStorage() throws Exception {
        String body="{\"eventId\":\"\",\"amountMinor\":-1,\"currency\":\"bad\"}";
        http.perform(post("/api/webhooks/acme").header("X-Relay-Signature",sign(body)).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertThat(events.metrics().get("received")).isEqualTo(0L);
    }
    @Test void databaseFailureRollsBackClaimAndLedgerTogether() {
        var id=receive("acme",0).id(); Instant t=now();
        jdbc.update("INSERT INTO demo_ledger(id,event_id,tenant,amount_minor,currency,created_at) VALUES('existing',?,'acme',4900,'USD',CURRENT_TIMESTAMP)",id);
        assertThatThrownBy(()->worker.process(id,t)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_event WHERE id=?",String.class,id)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM webhook_event WHERE id=?",Integer.class,id)).isZero();
        assertThat(events.attempts(id)).isEmpty();
    }
    private String sign(String raw) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
