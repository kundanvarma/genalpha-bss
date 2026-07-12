package com.bss.som.listen;

import com.bss.som.security.TenantContext;
import com.bss.som.service.OrchestrationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The BSS→SOM handoff: new product orders arrive as events and the SOM
 * takes it from there. Acts as the producing tenant so persistence and the
 * completion callback both happen inside the right tenant.
 */
@Component
@ConditionalOnProperty(name = "bss.som.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final OrchestrationService orchestration;
    private final ObjectMapper objectMapper;

    public OrderEventListener(OrchestrationService orchestration, ObjectMapper objectMapper) {
        this.orchestration = orchestration;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.som.order-topic:bss.ordering.events}", groupId = "som")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String type = String.valueOf(envelope.get("eventType"));
            // Create: digital orders activate instantly. StateChange(completed):
            // a physically-fulfilled order is done shipping — provision it now.
            if (!"ProductOrderCreateEvent".equals(type) && !"ProductOrderStateChangeEvent".equals(type)) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Object order = event.get("productOrder");
            if (order instanceof Map<?, ?> po) {
                try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                    orchestration.orchestrate((Map<String, Object>) po);
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable order event: {}", e.getMessage());
        }
    }
}
