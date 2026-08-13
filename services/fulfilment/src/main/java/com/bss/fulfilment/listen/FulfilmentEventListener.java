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
                    // A bundle order is a tree: the shippable goods (a handset, a
                    // physical SIM) are LEAF components, not top-level items. Walk to
                    // the leaves so each parcels on its own — never the bundle as one.
                    List<Map<String, Object>> physicalItems = new ArrayList<>();
                    if (resource.get("productOrderItem") instanceof List<?> items) {
                        collectShippableLeaves((List<Map<String, Object>>) items, physicalItems);
                    }
                    Object place = physicalItems.isEmpty() ? null
                            : ((Map<String, Object>) physicalItems.get(0).get("product")).get("place");
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

    /**
     * Walk a decomposed order to its shippable leaf components. A leaf that
     * carries a place and is not an install (fiber/broadband go via a workOrder,
     * not a parcel) is a physical good to dispatch; a container (a bundle with
     * children) is never shipped itself.
     */
    @SuppressWarnings("unchecked")
    private static void collectShippableLeaves(List<Map<String, Object>> items,
            List<Map<String, Object>> into) {
        if (items == null) {
            return;
        }
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> item)) {
                continue;
            }
            Object kids = item.get("productOrderItem");
            if (kids instanceof List<?> children && !children.isEmpty()) {
                collectShippableLeaves((List<Map<String, Object>>) children, into);
            } else if (item.get("product") instanceof Map<?, ?> product
                    && product.get("place") != null
                    && !installsRatherThanShips((Map<String, Object>) item)) {
                into.add((Map<String, Object>) item);
            }
        }
    }

    /**
     * A broadband/fiber line's place is the INSTALL address (an engineer visit
     * via the workOrder), not a shipping address — it must not be booked as a
     * carrier parcel. Heuristic on the offering name; a fuller version would
     * read the place role or a catalog fulfilment-type flag.
     */
    private static boolean installsRatherThanShips(Map<?, ?> item) {
        // An installation-role place is the ENGINEER's address (a workOrder visit),
        // never a parcel — the most reliable signal that a line installs, not ships.
        if (item.get("product") instanceof Map<?, ?> product
                && product.get("place") instanceof List<?> places) {
            for (Object pl : places) {
                if (pl instanceof Map<?, ?> place && place.get("role") != null
                        && String.valueOf(place.get("role")).toLowerCase().contains("install")) {
                    return true;
                }
            }
        }
        // Fallback on the offering name for broadband lines carrying a generic place.
        Object off = item.get("productOffering");
        String name = off instanceof Map<?, ?> m && m.get("name") != null
                ? String.valueOf(m.get("name")).toLowerCase() : "";
        return name.contains("fiber") || name.contains("fibre") || name.contains("broadband")
                || name.contains("internet") || name.contains("dsl");
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
