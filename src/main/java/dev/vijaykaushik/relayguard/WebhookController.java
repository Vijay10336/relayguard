package dev.vijaykaushik.relayguard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class WebhookController {
    private final EventService events;
    private final ObjectMapper json;
    private final Validator validator;
    private final String secret;
    public record Payment(@NotBlank @Size(max=120) String eventId, @Min(1) long amountMinor,
                          @NotBlank @Pattern(regexp="[A-Z]{3}") String currency) {}
    public WebhookController(EventService events, ObjectMapper json, Validator validator,
                             @Value("${relayguard.webhook-secret}") String secret) {
        this.events=events; this.json=json; this.validator=validator; this.secret=secret;
    }
    @PostMapping("/webhooks/{tenant}")
    public ResponseEntity<EventService.Receipt> receive(@PathVariable String tenant, @RequestBody String raw,
            @RequestHeader(value="X-Relay-Signature",defaultValue="") String signature) {
        if (raw.length()>8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large");
        if (!MessageDigest.isEqual(sign(raw).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        try {
            Payment p=json.readValue(raw, Payment.class);
            if (p==null || !validator.validate(p).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment event");
            var receipt=events.accept(tenant,p.eventId(),p.amountMinor(),p.currency(),raw,0);
            return ResponseEntity.status(receipt.duplicate()?HttpStatus.OK:HttpStatus.ACCEPTED).body(receipt);
        } catch(JsonProcessingException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid JSON"); }
    }
    @GetMapping("/events") public List<Map<String,Object>> events() { return events.events(); }
    @GetMapping("/metrics") public Map<String,Object> metrics() { return events.metrics(); }
    @GetMapping("/events/{id}/attempts") public List<Map<String,Object>> attempts(@PathVariable String id) { return events.attempts(id); }
    @PostMapping("/events/{id}/replay") public Map<String,String> replay(@PathVariable String id) {
        events.replay(id); return Map.of("status","QUEUED","note","Demo downstream failure has been cleared; history is retained");
    }
    @PostMapping("/demo/{scenario}") public Map<String,Object> demo(@PathVariable String scenario) throws JsonProcessingException {
        if (!List.of("success","duplicate","retry","poison","tenant").contains(scenario))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown scenario");
        String key="evt_"+UUID.randomUUID().toString().substring(0,8);
        String raw=json.writeValueAsString(new Payment(key,4900,"USD"));
        int failures=scenario.equals("retry")?2:scenario.equals("poison")?99:0;
        var first=events.accept("acme",key,4900,"USD",raw,failures);
        if (scenario.equals("duplicate")) {
            var again=events.accept("acme",key,4900,"USD",raw,failures);
            return Map.of("ids",List.of(first.id()),"message","Same event sent twice. Duplicate detected: "+again.duplicate());
        }
        if (scenario.equals("tenant")) {
            var second=events.accept("orbit",key,4900,"USD",raw,0);
            return Map.of("ids",List.of(first.id(),second.id()),"message","Same event key, two tenants. Each gets its own ledger entry.");
        }
        return Map.of("ids",List.of(first.id()),"message",switch(scenario) {
            case "retry" -> "Two injected failures, then recovery. Watch the attempt timeline.";
            case "poison" -> "Three failed attempts lead to dead letter. Replay clears the simulated outage.";
            default -> "Payment event accepted. The worker will write one demo ledger entry.";
        });
    }
    private String sign(String raw) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
