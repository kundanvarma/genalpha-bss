package com.bss.loyalty.listen;

import com.bss.loyalty.security.TenantContext;
import com.bss.loyalty.service.LoyaltyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The earner: loyalty follows the BILLING relationship. A settled bill
 * (CustomerBillStateChangeEvent, state=settled) earns points for its
 * customer — idempotent per bill, opt-in members only, at the program's
 * data-defined rate.
 */
@Component
public class BillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);

    private final LoyaltyService loyalty;
    private final ObjectMapper objectMapper;

    public BillingEventListener(LoyaltyService loyalty, ObjectMapper objectMapper) {
        this.loyalty = loyalty;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.loyalty.billing-topic:bss.billing.events}", groupId = "loyalty")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, Map.class);
            if (!"CustomerBillStateChangeEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = String.valueOf(envelope.getOrDefault("tenantId", "genalpha"));
            Map<String, Object> event = (Map<String, Object>) envelope.getOrDefault("event", Map.of());
            Map<String, Object> bill = (Map<String, Object>) event.get("customerBill");
            if (bill == null || !"settled".equals(bill.get("state"))) {
                return;
            }
            String billId = String.valueOf(bill.get("id"));
            String party = ((List<Map<String, Object>>) bill.getOrDefault("relatedParty", List.of()))
                    .stream().filter(p -> "customer".equals(p.get("role")))
                    .map(p -> String.valueOf(p.get("id"))).findFirst().orElse(null);
            Map<String, Object> amountDue = (Map<String, Object>) bill.get("amountDue");
            BigDecimal amount = amountDue == null || amountDue.get("value") == null ? null
                    : new BigDecimal(String.valueOf(amountDue.get("value")));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                loyalty.earnOnSettledBill(tenantId, party, billId, amount);
            }
        } catch (Exception e) {
            log.warn("loyalty earn skipped: {}", e.getMessage());
        }
    }
}
