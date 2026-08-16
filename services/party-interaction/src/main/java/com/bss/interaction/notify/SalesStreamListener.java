package com.bss.interaction.notify;

import com.bss.interaction.security.TenantContext;
import com.bss.interaction.service.PartyInteractionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sales on the 360: when a deal we're working is WITH a party we already know
 * (a B2B expansion, an existing account), every activity the sales team logs
 * on the opportunity — a call, an email, a note, "moved to Proposal", "Won" —
 * lands on that party's TMF683 timeline. So the CSR/AM reads sales and service
 * as one record. Activities on a pure prospect (no party id) stay on the
 * opportunity's own workspace and never reach here.
 */
@Component
@ConditionalOnProperty(name = "bss.interaction.consumer-enabled", havingValue = "true",
        matchIfMissing = true)
public class SalesStreamListener {

    private static final Logger log = LoggerFactory.getLogger(SalesStreamListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final PartyInteractionService interactions;
    private final ObjectMapper objectMapper;

    public SalesStreamListener(PartyInteractionService interactions, ObjectMapper objectMapper) {
        this.interactions = interactions;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.interaction.sales-topic:bss.quote.events}",
            groupId = "party-interaction-sales")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"SalesActivityCreateEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            Map<String, Object> activity = event.get("salesActivity") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            String party = activity.get("partyId") == null ? null : String.valueOf(activity.get("partyId"));
            if (party == null || activity.get("note") == null) {
                return; // pure-prospect activity, or nothing to say — not on the 360
            }
            String deal = activity.get("opportunityName") == null ? "deal"
                    : String.valueOf(activity.get("opportunityName"));
            String type = String.valueOf(activity.get("type"));
            String channel = "call".equals(type) ? "phone" : "email".equals(type) ? "email" : "sales";
            String description = "Sales (" + deal + "): " + activity.get("note");
            String sourceRef = String.valueOf(activity.get("id"));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                interactions.mintTouchpoint(sourceRef, "sales", description, channel, party);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable sales event: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
