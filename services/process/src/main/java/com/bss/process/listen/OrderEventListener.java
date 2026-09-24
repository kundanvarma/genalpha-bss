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
import static com.bss.process.api.Wire.idOf;
import static com.bss.process.api.Wire.textOf;

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

    @KafkaListener(topics = "${bss.process.fulfilment-topic:bss.fulfilment.events}", groupId = "process")
    public void onFulfilmentEvent(String payload) {
        handle(payload, "bss.fulfilment.events");
    }

    @KafkaListener(topics = "${bss.process.catalog-topic:bss.catalog.events}", groupId = "process")
    public void onCatalogEvent(String payload) {
        handle(payload, "bss.catalog.events");
    }

    @SuppressWarnings("unchecked")
    private void handle(String payload, String topic) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = java.util.Objects.requireNonNullElse(textOf(envelope, "tenantId"), "genalpha");
            Map<String, Object> resource = envelope.get("event") instanceof Map<?, ?> event
                    ? event.values().stream().filter(v -> v instanceof Map)
                            .map(v -> (Map<String, Object>) v).findFirst().orElse(Map.of())
                    : Map.of();
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                switch (eventType) {
                    case "ProductOrderCreateEvent" -> {
                        String orderId = idOf(resource);
                        if (orderId != null) {
                            service.onOrderCreated(orderId, partyOf(resource),
                                    isPhysical(resource), resource);
                        }
                    }
                    case "ProductOrderStateChangeEvent" -> {
                        // the one case that had no guard: a state change on no order used
                        // to journal a milestone onto the flow of the order "null"
                        String orderId = idOf(resource);
                        String state = orderId == null ? "" : String.valueOf(resource.get("state"));
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
                    case "ShippingOrderCreateEvent", "ShippingOrderStateChangeEvent",
                         "WorkOrderCreateEvent", "WorkOrderStateChangeEvent" -> {
                        String orderId = textOf(resource, "productOrderId");
                        if (orderId != null) {
                            // milestones journal onto the flow's timeline; 'delivered'
                            // also completes the physical 'fulfilled' task
                            if ("delivered".equals(resource.get("state"))) {
                                service.onMilestone(orderId, "fulfilled", eventType, topic, resource);
                            } else {
                                service.recordOnly(orderId, eventType, topic, resource);
                            }
                        }
                    }
                    case "ServiceOrderStateChangeEvent" -> {
                        String orderId = textOf(resource, "productOrderId");
                        if (orderId != null && "completed".equals(resource.get("state"))) {
                            // physical flows: fulfilment implicitly done when SOM provisions
                            service.onMilestone(orderId, "provisioned", eventType, topic, resource);
                        }
                    }
                    case "ProductOfferingGovernanceEvent" -> {
                        String offeringId = idOf(resource);
                        if (offeringId != null) {
                            service.onGovernance(offeringId, String.valueOf(resource.get("action")), resource, topic);
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
                String id = idOf(p);
                String role = textOf(p, "role");
                if (id != null && (role == null || "customer".equalsIgnoreCase(role))) {
                    return id;
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
