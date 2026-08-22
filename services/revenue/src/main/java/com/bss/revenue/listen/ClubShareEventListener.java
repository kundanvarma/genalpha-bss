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

/** H2 — the subledger's ear on the dugnad: a club-linked referral that paid
 *  out accrues the club's share as expense + payable, idempotent per
 *  conversion. */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class ClubShareEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClubShareEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public ClubShareEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.campaign-topic:bss.campaign.events}",
            groupId = "revenue-club-share")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"ClubShareAccruedEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> share = BillingEventListener.resource(envelope, "clubShare");
            if (share != null) {
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    revenue.postClubShare(share);
                }
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable club-share event: {}", e.getMessage());
        }
    }
}
