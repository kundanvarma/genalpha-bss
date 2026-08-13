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
 * The subledger's ear on open access: a wholesale access line going live is a
 * cost of sale, booked as COGS + a payable to the fibre owner — so the retail
 * margin is real in the ledger, not only in the settlement view. At-least-once
 * delivery; the journal's unique source_ref makes replays free.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class WholesaleEventListener {

    private static final Logger log = LoggerFactory.getLogger(WholesaleEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public WholesaleEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.som-topic:bss.som.events}", groupId = "revenue-wholesale")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"WholesaleAccessOrderStateChangeEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            Map<String, Object> wao = BillingEventListener.resource(envelope, "wholesaleAccessOrder");
            if (!"active".equals(wao.get("state"))) {
                return; // only a live access line is a cost we owe
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                revenue.postWholesaleCogs(wao);
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable wholesale event: {}", e.getMessage());
        }
    }
}
