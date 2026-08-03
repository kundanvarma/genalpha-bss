package com.bss.hub.service;

import com.bss.hub.entity.HubDelivery;
import com.bss.hub.repository.HubDeliveryRepository;
import com.bss.hub.repository.HubSubscriptionRepository;
import com.bss.hub.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;

/** The delivery half: exponential backoff, DEAD after max attempts —
 * never lost, never silent, always accountable (the distribution-relay
 * doctrine, applied to webhooks). */
@Component
public class HubRelay {

    private static final Logger log = LoggerFactory.getLogger(HubRelay.class);

    private final HubDeliveryRepository deliveries;
    private final HubSubscriptionRepository subscriptions;
    private final TickGuard guard;
    private final RestClient rest;
    private final long retryBaseMs;
    private final int maxAttempts;

    public HubRelay(HubDeliveryRepository deliveries, HubSubscriptionRepository subscriptions,
            TickGuard guard, RestClient.Builder builder,
            @Value("${bss.hub.retry-base-ms:5000}") long retryBaseMs,
            @Value("${bss.hub.max-attempts:4}") int maxAttempts) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.guard = guard;
        this.rest = builder.build();
        this.retryBaseMs = retryBaseMs;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${bss.hub.relay-interval-ms:3000}")
    public void deliverTick() {
        if (!guard.claim("hub-relay", Duration.ofSeconds(30))) {
            return;
        }
        for (HubDelivery d : deliveries.findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                HubDelivery.PENDING, OffsetDateTime.now())) {
            String callback = subscriptions.findById(d.getSubscriptionId())
                    .map(s -> s.isActive() ? s.getCallback() : null).orElse(null);
            if (callback == null) {
                d.setStatus(HubDelivery.DEAD);
                d.setLastError("subscription gone or inactive");
                deliveries.save(d);
                continue;
            }
            d.setAttempts(d.getAttempts() + 1);
            try {
                rest.post().uri(callback).contentType(MediaType.APPLICATION_JSON)
                        .body(d.getPayload()).retrieve().toBodilessEntity();
                d.setStatus(HubDelivery.DELIVERED);
                d.setLastError(null);
            } catch (Exception e) {
                String err = e.getMessage() == null ? "delivery failed"
                        : e.getMessage().substring(0, Math.min(250, e.getMessage().length()));
                d.setLastError(err);
                if (d.getAttempts() >= maxAttempts) {
                    d.setStatus(HubDelivery.DEAD);
                    log.warn("hub: delivery {} DEAD after {} attempts ({})",
                            d.getId(), d.getAttempts(), err);
                } else {
                    // exponential backoff: base * 2^(attempts-1)
                    d.setNextAttemptAt(OffsetDateTime.now()
                            .plusNanos(retryBaseMs * (1L << (d.getAttempts() - 1)) * 1_000_000L));
                }
            }
            deliveries.save(d);
        }
    }
}
