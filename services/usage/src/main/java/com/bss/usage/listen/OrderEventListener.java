package com.bss.usage.listen;

import com.bss.usage.security.TenantContext;
import com.bss.usage.service.UsageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import static com.bss.usage.api.Wire.textOf;

/**
 * Data top-ups land here: a completed order carrying a boost-flagged offering
 * adds allowance to the buyer's current period. Same event stream the SOM
 * consumes — usage just looks for a different thing in it.
 */
@Component
@ConditionalOnProperty(name = "bss.usage.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final UsageService usage;
    private final ObjectMapper objectMapper;

    public OrderEventListener(UsageService usage, ObjectMapper objectMapper) {
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = { "${bss.usage.order-topic:bss.ordering.events}",
            "${bss.usage.loyalty-topic:bss.loyalty.events}",
            "${bss.usage.campaign-topic:bss.campaign.events}" }, groupId = "usage")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            if ("LoyaltyDataRewardEvent".equals(eventType)) {
                // points became gigabytes on the loyalty ledger; the meter
                // delivers them — idempotent per redemption
                String tenant = java.util.Objects.requireNonNullElse(textOf(envelope, "tenantId"), "genalpha");
                Map<String, Object> ev = envelope.get("event") instanceof Map<?, ?> em
                        ? (Map<String, Object>) em : Map.of();
                if (ev.get("loyaltyReward") instanceof Map<?, ?> rw) {
                    String party = textOf(rw, "partyId");
                    String redemptionId = textOf(rw, "redemptionId");
                    if (party == null || redemptionId == null) {
                        // gigabytes for nobody, or without the redemption that makes them once-only
                        log.warn("loyalty reward without a party or redemption id — not delivered: {}", rw.keySet());
                        return;
                    }
                    try (TenantContext ignored = TenantContext.actAs(tenant)) {
                        usage.recordLoyaltyBoost(tenant, party,
                                new java.math.BigDecimal(String.valueOf(rw.get("gb"))), redemptionId);
                    }
                }
                return;
            }
            if ("ReferralDataRewardEvent".equals(eventType)) {
                // G1: a referral became a customer — both sides get GBs on the
                // meter, same idempotent rail as a loyalty redemption
                String tenant = java.util.Objects.requireNonNullElse(textOf(envelope, "tenantId"), "genalpha");
                Map<String, Object> ev = envelope.get("event") instanceof Map<?, ?> em
                        ? (Map<String, Object>) em : Map.of();
                if (ev.get("referralReward") instanceof Map<?, ?> rw) {
                    String party = textOf(rw, "partyId");
                    if (party == null) {
                        log.warn("referral reward without a party — not delivered: {}", rw.keySet());
                        return;
                    }
                    try (TenantContext ignored = TenantContext.actAs(tenant)) {
                        java.math.BigDecimal gb = new java.math.BigDecimal(String.valueOf(rw.get("gb")));
                        String rewardId = "referral-" + rw.get("rewardId");
                        if (!usage.recordLoyaltyBoost(tenant, party, gb, rewardId)) {
                            usage.parkReward(tenant, party, gb, rewardId);
                        }
                    }
                }
                return;
            }
            if (!"ProductOrderStateChangeEvent".equals(eventType)) {
                return;
            }
            String tenantId = java.util.Objects.requireNonNullElse(textOf(envelope, "tenantId"), "genalpha");
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            if (event.get("productOrder") instanceof Map<?, ?> po) {
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    usage.recordTopUps((Map<String, Object>) po);
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable order event: {}", e.getMessage());
        }
    }
}
