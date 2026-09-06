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
 * The network's device truth (EIR: IMEI/TAC → model) reaches the SOM so a line
 * knows what handset it rides — slice eligibility reads it before a pass sells.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final OrchestrationService orchestration;
    private final ObjectMapper objectMapper;

    public DeviceEventListener(OrchestrationService orchestration, ObjectMapper objectMapper) {
        this.orchestration = orchestration;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.som.usage-topic:bss.usage.events}", groupId = "som-device")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"DeviceDetectedEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha" : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> resource = event.values().stream().filter(v -> v instanceof Map)
                    .map(v -> (Map<String, Object>) v).findFirst().orElse(Map.of());
            if (resource.get("partyId") == null || resource.get("deviceModel") == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                orchestration.onDeviceDetected(tenantId, String.valueOf(resource.get("partyId")),
                        String.valueOf(resource.get("deviceModel")));
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable device event: {}", e.getMessage());
        }
    }
}
