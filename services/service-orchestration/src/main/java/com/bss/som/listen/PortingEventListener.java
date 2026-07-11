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

import java.util.List;
import java.util.Map;

/**
 * When a customer ports their number OUT — takes it to another operator —
 * the cutover completes and we no longer hold the number. The service is
 * ceased and the number released. This closes the port-out loop: the number
 * leaves, the service ends, and the departure becomes a churn signal.
 */
@Component
@ConditionalOnProperty(name = "bss.som.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class PortingEventListener {

    private static final Logger log = LoggerFactory.getLogger(PortingEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final OrchestrationService orchestration;
    private final ObjectMapper objectMapper;

    public PortingEventListener(OrchestrationService orchestration, ObjectMapper objectMapper) {
        this.orchestration = orchestration;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.som.porting-topic:bss.porting.events}", groupId = "som-porting")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            if (!"PortingOrderStateChangeEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenant = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Object porting = event.values().stream().filter(v -> v instanceof Map).findFirst().orElse(null);
            if (!(porting instanceof Map<?, ?> order)) {
                return;
            }
            if (!"portOut".equals(order.get("direction")) || !"completed".equals(order.get("status"))) {
                return;
            }
            String party = partyOf((Map<String, Object>) order);
            if (party == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenant)) {
                int ceased = orchestration.terminateForParty(party, "ported out").size();
                log.info("port-out completed for {} — ceased {} service(s)", party, ceased);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable porting event: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String partyOf(Map<String, Object> order) {
        if (order.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> p && p.get("id") != null) {
            return String.valueOf(p.get("id"));
        }
        return null;
    }
}
