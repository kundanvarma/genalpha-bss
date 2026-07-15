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
            if (!"CommunicationMessageCreateEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            Map<String, Object> message = event.get("communicationMessage") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            String party = partyOf(message);
            if (party == null || message.get("subject") == null) {
                return;
            }
            String channel = message.get("messageType") == null ? "inApp"
                    : String.valueOf(message.get("messageType"));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                interactions.mintTouchpoint(String.valueOf(envelope.get("eventId")),
                        "communication", "Message sent: " + message.get("subject"),
                        channel, party);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable communication event: {}", e.getMessage());
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
