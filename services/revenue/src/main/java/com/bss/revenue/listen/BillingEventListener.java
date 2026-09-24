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
            String eventType = String.valueOf(envelope.get("eventType"));
            Object rawTenant = envelope.get("tenantId");
            String tenantId = rawTenant == null ? "genalpha" : String.valueOf(rawTenant);
            if ("CustomerBillCreateEvent".equals(eventType)) {
                Map<String, Object> bill = resource(envelope, "customerBill");
                Object billId = bill.get("id");
                if (billId == null) {
                    return;   // a bill with no id posts nothing to the subledger
                }
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    revenue.postBill(billId.toString(), bill);
                }
            } else if ("CreditNoteIssuedEvent".equals(eventType)) {
                Map<String, Object> creditNote = resource(envelope, "creditNote");
                // REFUNDED notes moved money via the PSP — the refund event
                // books that; only REDUCED notes book contra-revenue here
                Object noteId = creditNote.get("id");
                if ("reduced".equals(creditNote.get("settlement")) && noteId != null) {
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postCreditNote(noteId.toString(), creditNote);
                    }
                }
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
