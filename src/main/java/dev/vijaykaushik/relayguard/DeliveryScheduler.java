package dev.vijaykaushik.relayguard;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="relayguard.worker-enabled", havingValue="true", matchIfMissing=true)
public class DeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);
    private final EventService events;
    private final DeliveryWorker worker;
    public DeliveryScheduler(EventService events, DeliveryWorker worker) { this.events = events; this.worker = worker; }
    @Scheduled(fixedDelayString="${relayguard.worker-delay-ms:2000}")
    public void tick() {
        Instant now = Instant.now();
        for (String id : events.due(now)) {
            try { worker.process(id, now); }
            catch (RuntimeException ex) { log.error("Delivery transaction rolled back for event {}", id, ex); }
        }
    }
}
