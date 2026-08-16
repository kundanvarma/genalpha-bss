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

import java.util.List;
import java.util.Map;

/**
 * The OMNICHANNEL ear: every customer message the communication component
 * sends — martech blasts, journey steps, order notifications, whatever the
 * in-app/ESP seam delivered — flows past on the event stream and lands on
 * the TMF683 timeline. Together with the open POST (any system in the
 * landscape may log a call, a store visit, a chat), the interaction log
 * becomes the one place a CSR reads before speaking: what have we already
 * said to this customer, on which channel, from which system.
 */
@Component
@ConditionalOnProperty(name = "bss.interaction.consumer-enabled", havingValue = "true",
        matchIfMissing = true)
public class CommunicationStreamListener {

    private static final Logger log = LoggerFactory.getLogger(CommunicationStreamListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final PartyInteractionService interactions;
    private final ObjectMapper objectMapper;

    public CommunicationStreamListener(PartyInteractionService interactions, ObjectMapper objectMapper) {
        this.interactions = interactions;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.interaction.communication-topic:bss.communication.events}",
            groupId = "party-interaction")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            if ("CommunicationMessageCreateEvent".equals(eventType)) {
                onMessageSent(envelope, event, tenantId);
            } else if ("EmailEngagedEvent".equals(eventType)) {
                onEngagement(envelope, event, tenantId);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable communication event: {}", e.getMessage());
        }
    }

    /** A message left the building — record "we said X to this customer". */
    private void onMessageSent(Map<String, Object> envelope, Map<String, Object> event, String tenantId) {
        Map<String, Object> message = event.get("communicationMessage") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        String party = partyOf(message);
        if (party == null || message.get("subject") == null) {
            return;
        }
        String channel = message.get("messageType") == null ? "inApp"
                : String.valueOf(message.get("messageType"));
        // A campaign/journey stamps its name as `source` — a CSR reads
        // "Marketing (Winback): ..." instead of a bare subject.
        String source = message.get("source") == null ? null : String.valueOf(message.get("source"));
        String description = source != null
                ? "Marketing (" + source + "): " + message.get("subject")
                : "Message sent: " + message.get("subject");
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            interactions.mintTouchpoint(String.valueOf(envelope.get("eventId")),
                    "communication", description, channel, party);
        }
    }

    /** The customer opened or clicked — close the loop on the same timeline. */
    private void onEngagement(Map<String, Object> envelope, Map<String, Object> event, String tenantId) {
        Map<String, Object> engagement = event.get("engagement") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        String party = engagement.get("partyId") == null ? null : String.valueOf(engagement.get("partyId"));
        String verdict = String.valueOf(engagement.get("engagement"));
        if (party == null || (!"open".equals(verdict) && !"click".equals(verdict))) {
            return;
        }
        String description = "click".equals(verdict) ? "Email clicked" : "Email opened";
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            interactions.mintTouchpoint(String.valueOf(envelope.get("eventId")),
                    "communication", description, "email", party);
        }
    }

    private String partyOf(Map<String, Object> message) {
        if (message.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("id") != null
                        && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
