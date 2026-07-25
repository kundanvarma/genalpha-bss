package com.bss.revenue.listen;

import com.bss.revenue.security.TenantContext;
import com.bss.revenue.service.RevenueService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The subledger's ear on billing: an invoice issued becomes a balanced
 * AR/revenue posting. Delivery is at-least-once; the journal's unique
 * source_ref makes replays free.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class BillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public BillingEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.billing-topic:bss.billing.events}", groupId = "revenue")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"CustomerBillCreateEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> bill = resource(envelope, "customerBill");
            if (bill.get("id") == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                revenue.postBill(String.valueOf(bill.get("id")), bill);
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable billing event: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resource(Map<String, Object> envelope, String key) {
        return envelope.get("event") instanceof Map<?, ?> event
                && event.get(key) instanceof Map<?, ?> resource
                ? (Map<String, Object>) resource : Map.of();
    }
}
