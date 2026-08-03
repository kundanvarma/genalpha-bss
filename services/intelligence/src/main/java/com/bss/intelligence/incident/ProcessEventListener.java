package com.bss.intelligence.incident;

import com.bss.intelligence.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** The agent's trigger: a taskFlow going FAILED on the process bus. */
@Component
@ConditionalOnProperty(name = "bss.intelligence.incident-consumer-enabled",
        havingValue = "true", matchIfMissing = true)
public class ProcessEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProcessEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final IncidentAgentService agent;
    private final ObjectMapper objectMapper;

    public ProcessEventListener(IncidentAgentService agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.intelligence.process-topic:bss.process.events}",
            groupId = "intelligence-incident")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"TaskFlowStateChangeEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> task = envelope.get("event") instanceof Map<?, ?> event
                    && event.get("taskFlow") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : Map.of();
            if (!"failed".equals(task.get("state"))) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                agent.onTaskFailed(task);
            }
        } catch (Exception e) {
            log.warn("incident: skipping unprocessable process event: {}", e.getMessage());
        }
    }
}
