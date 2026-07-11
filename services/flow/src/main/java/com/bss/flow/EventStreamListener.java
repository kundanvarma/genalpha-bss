package com.bss.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ear on the whole bus: one pattern subscription to every bss.*.events
 * topic. Each envelope becomes a compact "move" — who produced it, who
 * reacts, the tenant, a masked reference — never the full payload (this is
 * an observability surface, not a data tap).
 */
@Component
public class EventStreamListener {

    private static final Logger log = LoggerFactory.getLogger(EventStreamListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final FlowBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public EventStreamListener(FlowBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topicPattern = "bss\\..*\\.events", groupId = "flow")
    public void onEvent(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            String source = componentOf(topic);
            String eventType = String.valueOf(envelope.getOrDefault("eventType", "Event"));
            String tenant = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));

            Map<String, Object> move = new LinkedHashMap<>();
            move.put("eventId", String.valueOf(envelope.getOrDefault("eventId", "")));
            move.put("eventTime", String.valueOf(envelope.getOrDefault("eventTime", "")));
            move.put("eventType", eventType);
            move.put("source", source);
            move.put("tenant", tenant);
            move.put("reactors", Choreography.reactorsFor(eventType));
            move.put("ref", referenceOf(envelope));
            broadcaster.broadcast(move);
        } catch (Exception e) {
            log.debug("skipping unparseable event on {}: {}", topic, e.getMessage());
        }
    }

    /** bss.ordering.events -> ordering ; bss.som.events -> service-orchestration */
    private static String componentOf(String topic) {
        String c = topic.replaceFirst("^bss\\.", "").replaceFirst("\\.events$", "");
        return switch (c) {
            case "som" -> "service-orchestration";
            case "cart" -> "shopping-cart";
            case "ticket" -> "trouble-ticket";
            case "ordering" -> "product-ordering";
            case "inventory" -> "product-inventory";
            case "catalog" -> "product-catalog";
            default -> c;
        };
    }

    /** A masked hint of the resource/customer — enough to follow a thread, no PII. */
    @SuppressWarnings("unchecked")
    private static String referenceOf(Map<String, Object> envelope) {
        Object eventObj = envelope.get("event");
        if (!(eventObj instanceof Map<?, ?> event)) {
            return "";
        }
        for (Object value : ((Map<String, Object>) event).values()) {
            if (value instanceof Map<?, ?> resource) {
                if (resource.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                        && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
                    return "party " + mask(String.valueOf(party.get("id")));
                }
                if (resource.get("id") != null) {
                    return "#" + mask(String.valueOf(resource.get("id")));
                }
            }
        }
        return "";
    }

    private static String mask(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }
}
