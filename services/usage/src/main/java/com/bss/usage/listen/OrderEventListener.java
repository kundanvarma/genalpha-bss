package com.bss.usage.listen;

import com.bss.usage.security.TenantContext;
import com.bss.usage.service.UsageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Data top-ups land here: a completed order carrying a boost-flagged offering
 * adds allowance to the buyer's current period. Same event stream the SOM
 * consumes — usage just looks for a different thing in it.
 */
@Component
@ConditionalOnProperty(name = "bss.usage.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final UsageService usage;
    private final ObjectMapper objectMapper;

    public OrderEventListener(UsageService usage, ObjectMapper objectMapper) {
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.usage.order-topic:bss.ordering.events}", groupId = "usage")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"ProductOrderStateChangeEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            if (event.get("productOrder") instanceof Map<?, ?> po) {
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    usage.recordTopUps((Map<String, Object>) po);
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable order event: {}", e.getMessage());
        }
    }
}
