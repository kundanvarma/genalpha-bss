package com.bss.fulfilment.listen;

import com.bss.fulfilment.security.TenantContext;
import com.bss.fulfilment.service.FulfilmentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Births: a physical order mints the parcel; an install booking mints the visit. */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class FulfilmentEventListener {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final FulfilmentService service;
    private final ObjectMapper objectMapper;

    public FulfilmentEventListener(FulfilmentService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.fulfilment.ordering-topic:bss.ordering.events}", groupId = "fulfilment")
    public void onOrderingEvent(String payload) {
        handle(payload);
    }

    @KafkaListener(topics = "${bss.fulfilment.appointment-topic:bss.appointment.events}", groupId = "fulfilment")
    public void onAppointmentEvent(String payload) {
        handle(payload);
    }

    @SuppressWarnings("unchecked")
    private void handle(String payload) {
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
                if ("ProductOrderCreateEvent".equals(eventType)) {
                    List<Map<String, Object>> physicalItems = new ArrayList<>();
                    Object place = null;
                    if (resource.get("productOrderItem") instanceof List<?> items) {
                        for (Object o : items) {
                            if (o instanceof Map<?, ?> item
                                    && item.get("product") instanceof Map<?, ?> product
                                    && product.get("place") != null) {
                                physicalItems.add((Map<String, Object>) item);
                                place = product.get("place");
                            }
                        }
                    }
                    if (!physicalItems.isEmpty() && resource.get("id") != null) {
                        service.onPhysicalOrder(String.valueOf(resource.get("id")),
                                partyOf(resource), physicalItems, place);
                    }
                } else if ("AppointmentCreateEvent".equals(eventType)) {
                    String orderId = null;
                    if (resource.get("relatedEntity") instanceof List<?> refs) {
                        for (Object r : refs) {
                            if (r instanceof Map<?, ?> ref
                                    && "ProductOrder".equals(ref.get("@referredType"))
                                    && ref.get("id") != null) {
                                orderId = String.valueOf(ref.get("id"));
                            }
                        }
                    }
                    if (orderId != null && resource.get("id") != null) {
                        service.onInstallAppointment(String.valueOf(resource.get("id")),
                                orderId, partyOf(resource), resource.get("place"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("fulfilment: skipping unprocessable event: {}", e.getMessage());
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
        if (resource.get("ownerPartyId") != null) {
            return String.valueOf(resource.get("ownerPartyId"));
        }
        return null;
    }
}
