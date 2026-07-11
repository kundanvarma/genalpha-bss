package com.bss.intelligence.churn;

import com.bss.intelligence.security.TenantContext;
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
 * A completed port-out is the strongest churn signal there is: a customer who
 * actually left, taking their number. Recording it as a labeled outcome
 * (churned = true) is exactly the ground truth the churn model trains on —
 * the loop from "predicted at risk" to "confirmed gone" closes here.
 */
@Component
@ConditionalOnProperty(name = "bss.intelligence.porting-consumer-enabled",
        havingValue = "true", matchIfMissing = true)
public class PortOutChurnListener {

    private static final Logger log = LoggerFactory.getLogger(PortOutChurnListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final ChurnModelService models;
    private final ObjectMapper objectMapper;

    public PortOutChurnListener(ChurnModelService models, ObjectMapper objectMapper) {
        this.models = models;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.intelligence.porting-topic:bss.porting.events}", groupId = "intelligence-porting")
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
            if (!(porting instanceof Map<?, ?> order)
                    || !"portOut".equals(order.get("direction"))
                    || !"completed".equals(order.get("status"))) {
                return;
            }
            String party = partyOf((Map<String, Object>) order);
            if (party == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenant)) {
                models.recordOutcome(Map.of("party", Map.of("id", party), "churned", true));
                log.info("port-out recorded as churn outcome for {}", party);
            }
        } catch (Exception e) {
            log.warn("skipping porting event: {}", e.getMessage());
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
