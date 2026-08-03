package com.bss.process.listen;

import com.bss.process.security.TenantContext;
import com.bss.process.service.ProcessFlowService;
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
 * The projection's ears: ordering and SOM events become process-flow
 * state. The correlation recipe is Live Flow's, persisted this time —
 * ordering events carry the order as resource.id; SOM events carry
 * productOrderId as a SIBLING field (the join-key trap, honored here).
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ProcessFlowService service;
    private final ObjectMapper objectMapper;

    public OrderEventListener(ProcessFlowService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.process.ordering-topic:bss.ordering.events}", groupId = "process")
    public void onOrderingEvent(String payload) {
        handle(payload, "bss.ordering.events");
    }

    @KafkaListener(topics = "${bss.process.som-topic:bss.som.events}", groupId = "process")
    public void onSomEvent(String payload) {
        handle(payload, "bss.som.events");
    }

    @SuppressWarnings("unchecked")
    private void handle(String payload, String topic) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> resource = envelope.get("event") instanceof Map<?, ?> event
                    ? event.values().stream().filter(v -> v instanceof Map)
                            .map(v -> (Map<String, Object>) v).findFirst().orElse(Map.of())
                    : Map.of();
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                switch (eventType) {
                    case "ProductOrderCreateEvent" -> {
                        String orderId = String.valueOf(resource.get("id"));
                        if (!"null".equals(orderId)) {
                            service.onOrderCreated(orderId, partyOf(resource),
                                    isPhysical(resource), resource);
                        }
                    }
                    case "ProductOrderStateChangeEvent" -> {
                        String orderId = String.valueOf(resource.get("id"));
                        String state = String.valueOf(resource.get("state"));
                        switch (state) {
                            case "completed" -> service.onMilestone(orderId, "completed",
                                    eventType, topic, resource);
                            case "held" -> service.onMilestone(orderId, "held",
                                    eventType, topic, resource);
                            case "cancelled" -> service.onMilestone(orderId, "cancelled",
                                    eventType, topic, resource);
                            default -> { }
                        }
                    }
                    case "ServiceOrderStateChangeEvent" -> {
                        String orderId = String.valueOf(resource.get("productOrderId"));
                        if (!"null".equals(orderId) && "completed".equals(resource.get("state"))) {
                            // physical flows: fulfilment implicitly done when SOM provisions
                            service.onMilestone(orderId, "provisioned", eventType, topic, resource);
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.warn("process: skipping unprocessable event: {}", e.getMessage());
        }
    }

    private static String partyOf(Map<String, Object> resource) {
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

    /** An item whose product carries a place needs hands: the physical spec. */
    private static boolean isPhysical(Map<String, Object> resource) {
        if (!(resource.get("productOrderItem") instanceof List<?> items)) {
            return false;
        }
        return items.stream().anyMatch(i -> i instanceof Map<?, ?> item
                && item.get("product") instanceof Map<?, ?> product
                && product.get("place") != null);
    }
}
