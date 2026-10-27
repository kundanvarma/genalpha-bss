package com.bss.revenue.listen;

import com.bss.revenue.security.TenantContext;
import com.bss.revenue.service.RevenueService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Benefit Choices (#360) — the subledger's ear on the benefit ledger: each
 *  month an active partner benefit costs is booked as cost of sales + a payable
 *  to the partner, idempotent per recorded month. */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerCostEventListener {

    private static final Logger log = LoggerFactory.getLogger(PartnerCostEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public PartnerCostEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.benefit-topic:bss.benefit.events}",
            groupId = "revenue-partner-cost")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"ChoiceCostRecordedEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            Object rawTenant = envelope.get("tenantId");
            String tenantId = rawTenant == null ? "genalpha" : String.valueOf(rawTenant);
            Map<String, Object> cost = BillingEventListener.resource(envelope, "partnerCost");
            if (cost != null) {
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    revenue.postPartnerCost(cost);
                }
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable partner-cost event: {}", e.getMessage());
        }
    }
}
