package dev.vijaykaushik.relayguard;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryWorker {
    private final JdbcTemplate jdbc;
    public DeliveryWorker(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean process(String id, Instant clock) {
        OffsetDateTime now = clock.atOffset(ZoneOffset.UTC);
        int claimed = jdbc.update("UPDATE webhook_event SET status='PROCESSING',attempts=attempts+1,updated_at=? WHERE id=? AND status IN ('RECEIVED','RETRY') AND next_attempt_at<=?", now, id, now);
        if (claimed == 0) return false;
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM webhook_event WHERE id=?", id);
        int number = ((Number) event.get("ATTEMPTS")).intValue();
        int failures = ((Number) event.get("FAIL_UNTIL_ATTEMPT")).intValue();
        boolean failing = number <= failures;
        String state = failing ? (number >= 3 ? "DEAD_LETTER" : "RETRY") : "DELIVERED";
        String detail = failing ? "Simulated downstream outage; no ledger entry written" : "Demo ledger entry committed in the same database transaction";
        if (!failing) {
            jdbc.update("INSERT INTO demo_ledger(id,event_id,tenant,amount_minor,currency,created_at) VALUES(?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), id, event.get("TENANT"), event.get("AMOUNT_MINOR"), event.get("CURRENCY"), now);
        }
        jdbc.update("INSERT INTO delivery_attempt(event_id,attempt_number,outcome,detail,created_at) VALUES(?,?,?,?,?)", id, number, state, detail, now);
        jdbc.update("UPDATE webhook_event SET status=?,next_attempt_at=?,updated_at=? WHERE id=?", state, now.plusSeconds(1L << Math.min(number, 10)), now, id);
        return true;
    }
}
