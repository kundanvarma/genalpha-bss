package com.bss.campaign.listen;

import com.bss.campaign.security.TenantContext;
import com.bss.campaign.service.CampaignService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The martech ear: every business event flows past; active campaigns decide
 * what matters. Acts as the producing tenant, extracts the customer from
 * the event's resource, and hands the engine one clean (type, state, party).
 */
@Component
@ConditionalOnProperty(name = "bss.campaign.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class BusinessEventListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final CampaignService campaigns;
    private final ObjectMapper objectMapper;
    private final com.bss.campaign.service.JourneyService journeys;

    public BusinessEventListener(CampaignService campaigns, ObjectMapper objectMapper,
            com.bss.campaign.service.JourneyService journeys) {
        this.campaigns = campaigns;
        this.objectMapper = objectMapper;
        this.journeys = journeys;
    }

    @KafkaListener(topics = "#{'${bss.campaign.topics}'.split(',')}", groupId = "campaign")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> resource = event.values().stream()
                    .filter(v -> v instanceof Map).map(v -> (Map<String, Object>) v)
                    .findFirst().orElse(Map.of());
            String party = partyOf(resource);
            if (party == null) {
                return;
            }
            String state = resource.get("state") != null ? String.valueOf(resource.get("state"))
                    : resource.get("status") != null ? String.valueOf(resource.get("status")) : null;
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                campaigns.onEvent(eventType, state, party);
                journeys.onEvent(eventType, state, party);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable event: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String partyOf(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("id") != null
                        && (ref.get("role") == null
                            || "customer".equalsIgnoreCase(String.valueOf(ref.get("role"))))) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }
}
