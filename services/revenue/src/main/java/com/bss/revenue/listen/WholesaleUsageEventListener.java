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
 * The subledger's ear on MOBILE wholesale: an MVNO's traffic rated at wholesale
 * for a period is a usage-metered cost of sale, booked as COGS + a payable to the
 * host MNO — so the retail mobile margin is real in the ledger. At-least-once
 * delivery; the journal's unique source_ref (mobile-wholesale:<ledger-id>) makes
 * replays free.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class WholesaleUsageEventListener {

    private static final Logger log = LoggerFactory.getLogger(WholesaleUsageEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public WholesaleUsageEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.usage-topic:bss.usage.events}", groupId = "revenue-mobile-wholesale")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String type = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            if ("WholesaleUsageRatedEvent".equals(type)) {
                // seeker side: the MVNO owes its host — book COGS
                Map<String, Object> row = BillingEventListener.resource(envelope, "wholesaleUsageLedger");
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    revenue.postMobileWholesaleCogs(row);
                }
            } else if ("ProviderWholesaleRatedEvent".equals(type)) {
                // provider side (W-M7): the host earns from an external MVNO — book revenue
                Map<String, Object> row = BillingEventListener.resource(envelope, "providerUsageLedger");
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    revenue.postMobileWholesaleRevenue(row);
                }
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable mobile-wholesale usage event: {}", e.getMessage());
        }
    }
}
