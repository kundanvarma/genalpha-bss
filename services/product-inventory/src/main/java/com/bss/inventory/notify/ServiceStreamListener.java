package com.bss.inventory.notify;

import com.bss.inventory.security.TenantContext;
import com.bss.inventory.service.ProductService;
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
 * When a service is ceased, the installed product must END with it —
 * status cancelled, terminationDate stamped — or billing keeps charging
 * a line that no longer exists. Acts as the producing tenant so the
 * row-level policies admit the write.
 */
@Component
@ConditionalOnProperty(name = "bss.inventory.consumer-enabled", havingValue = "true",
        matchIfMissing = true)
public class ServiceStreamListener {

    private static final Logger log = LoggerFactory.getLogger(ServiceStreamListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ProductService products;
    private final ObjectMapper objectMapper;

    public ServiceStreamListener(ProductService products, ObjectMapper objectMapper) {
        this.products = products;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.inventory.service-topic:bss.som.events}",
            groupId = "product-inventory")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"ServiceTerminatedEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            Map<String, Object> service = event.get("service") instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            String owner = partyOf(service);
            if (owner == null || service.get("name") == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                products.closeForTerminatedService(tenantId, owner,
                        String.valueOf(service.get("name")));
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable service event: {}", e.getMessage());
        }
    }

    private String partyOf(Map<String, Object> service) {
        if (service.get("relatedParty") instanceof List<?> parties) {
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
