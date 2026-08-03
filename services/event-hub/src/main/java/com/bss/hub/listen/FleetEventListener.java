package com.bss.hub.listen;

import com.bss.hub.service.HubService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** The ear on everything: every bss.*.events envelope, fanned to listeners. */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class FleetEventListener {

    private static final Logger log = LoggerFactory.getLogger(FleetEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final HubService hub;
    private final ObjectMapper objectMapper;

    public FleetEventListener(HubService hub, ObjectMapper objectMapper) {
        this.hub = hub;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topicPattern = "bss\\..*\\.events", groupId = "event-hub")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            hub.onFleetEvent(tenantId, eventType, payload);
        } catch (Exception e) {
            log.warn("hub: skipping unprocessable event: {}", e.getMessage());
        }
    }
}
