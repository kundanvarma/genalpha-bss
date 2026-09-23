package com.bss.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

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

            broadcaster.broadcast(new FlowMove(
                    String.valueOf(envelope.getOrDefault("eventId", "")),
                    String.valueOf(envelope.getOrDefault("eventTime", "")),
                    eventType,
                    source,
                    tenant,
                    Choreography.reactorsFor(eventType),
                    referenceOf(envelope),
                    correlationKeys(eventType, envelope)));
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

    /**
     * Correlation keys let the Process view thread events into the same
     * running instance — a party, an order, an intent flowing through stages.
     * The order↔party link comes from ProductOrderCreateEvent (it carries
     * both); service-order events carry only the order id, notifications only
     * the party — key intersection stitches them back together.
     */
    @SuppressWarnings("unchecked")
    private static FlowMove.CorrelationKeys correlationKeys(String eventType,
            Map<String, Object> envelope) {
        if (!(envelope.get("event") instanceof Map<?, ?> event)) {
            return FlowMove.CorrelationKeys.NONE;
        }
        Map<String, Object> resource = null;
        for (Object value : ((Map<String, Object>) event).values()) {
            if (value instanceof Map<?, ?> m) {
                resource = (Map<String, Object>) m;
                break;
            }
        }
        if (resource == null) {
            return FlowMove.CorrelationKeys.NONE;
        }
        // party — the through-line across most events
        String party = partyOf(resource);
        if (party == null && resource.get("partyId") != null) {
            party = String.valueOf(resource.get("partyId"));
        }
        // order
        String order = null;
        if (eventType.startsWith("ProductOrder") && resource.get("id") != null) {
            order = String.valueOf(resource.get("id"));
        } else if (resource.get("productOrderId") != null) {
            order = String.valueOf(resource.get("productOrderId"));
        } else if (resource.get("productOrder") instanceof Map<?, ?> po && po.get("id") != null) {
            order = String.valueOf(po.get("id"));
        }
        // intent
        String intent = null;
        if ("IntentCreateEvent".equals(eventType) && resource.get("id") != null) {
            intent = String.valueOf(resource.get("id"));
        } else if (resource.get("intent") instanceof Map<?, ?> in && in.get("id") != null) {
            intent = String.valueOf(in.get("id"));
        }
        // quote
        String quote = eventType.startsWith("Quote") && resource.get("id") != null
                ? String.valueOf(resource.get("id")) : null;
        // network object (delivery path / affected object) for the assurance stages
        String object = null;
        if (resource.get("affectedObject") != null) {
            object = String.valueOf(resource.get("affectedObject"));
        } else if (resource.get("from") != null) {
            object = String.valueOf(resource.get("from"));
        }
        return new FlowMove.CorrelationKeys(party, order, intent, quote, object);
    }

    @SuppressWarnings("unchecked")
    private static String partyOf(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            return String.valueOf(party.get("id"));
        }
        return null;
    }
}
