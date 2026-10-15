package dev.vijaykaushik.relayguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventService {
    private final JdbcTemplate jdbc;
    public EventService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Receipt(String id, boolean duplicate) {}

    public Receipt accept(String tenant, String key, long amount, String currency, String raw, int failures) {
        if (!tenant.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant slug");
        }
        String hash = hash(raw);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.update("INSERT INTO webhook_event(id,tenant,event_key,payload_hash,amount_minor,currency,status,fail_until_attempt,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'RECEIVED',?,?,?,?)",
                    id, tenant, key, hash, amount, currency, failures, now, now, now);
            return new Receipt(id, false);
        } catch (DuplicateKeyException e) {
            Map<String, Object> existing = jdbc.queryForMap("SELECT id,payload_hash FROM webhook_event WHERE tenant=? AND event_key=?", tenant, key);
            if (!hash.equals(existing.get("PAYLOAD_HASH"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Event key already used with a different payload");
            }
            return new Receipt((String) existing.get("ID"), true);
        }
    }

    public List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT id,tenant,event_key,amount_minor,currency,status,attempts,next_attempt_at,created_at FROM webhook_event ORDER BY created_at DESC LIMIT 100");
    }
    public List<Map<String, Object>> attempts(String id) {
        require(id);
        return jdbc.queryForList("SELECT attempt_number,outcome,detail,created_at FROM delivery_attempt WHERE event_id=? ORDER BY id", id);
    }
    public Map<String, Object> metrics() {
        return Map.of("received", count("SELECT COUNT(*) FROM webhook_event"),
                "delivered", count("SELECT COUNT(*) FROM webhook_event WHERE status='DELIVERED'"),
                "retrying", count("SELECT COUNT(*) FROM webhook_event WHERE status='RETRY'"),
                "dead", count("SELECT COUNT(*) FROM webhook_event WHERE status='DEAD_LETTER'"),
                "ledgerEntries", count("SELECT COUNT(*) FROM demo_ledger"));
    }
    public List<String> due(Instant now) {
        return jdbc.queryForList("SELECT id FROM webhook_event WHERE status IN ('RECEIVED','RETRY') AND next_attempt_at<=? ORDER BY created_at LIMIT 50", String.class, now.atOffset(ZoneOffset.UTC));
    }
    public void replay(String id) {
        require(id);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = jdbc.update("UPDATE webhook_event SET status='RECEIVED',next_attempt_at=?,updated_at=?,fail_until_attempt=0 WHERE id=? AND status='DEAD_LETTER'", now, now, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only dead-letter events can be replayed");
    }
    private void require(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM webhook_event WHERE id=?", Integer.class, id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
    }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
