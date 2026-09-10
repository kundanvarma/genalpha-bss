package com.bss.insight.listen;

import com.bss.insight.decision.DecisionLogService;
import com.bss.insight.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lands every service's decisions and outcomes in the log. Same doctrine as
 * the trait listener: the operational bus is the feed — a service that starts
 * deciding adaptively only has to publish {@code DecisionRecordedEvent} on its
 * own topic and be listed in {@code bss.insight.decision-topics}.
 */
@Component
public class DecisionLogListener {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private final DecisionLogService decisions;
    private final ObjectMapper objectMapper;

    public DecisionLogListener(DecisionLogService decisions, ObjectMapper objectMapper) {
        this.decisions = decisions;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.insight.decision-topics:bss.campaign.events,bss.intelligence.events}'.split(',')}",
            groupId = "insight-decisions")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            String type = String.valueOf(envelope.get("eventType"));
            if (!"DecisionRecordedEvent".equals(type) && !"DecisionOutcomeEvent".equals(type)) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha" : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                if ("DecisionRecordedEvent".equals(type)) {
                    Map<String, Object> decision = event.get("decision") instanceof Map<?, ?> d
                            ? (Map<String, Object>) d : Map.of();
                    decisions.record(tenantId, decision);
                } else {
                    Map<String, Object> outcome = event.get("decisionOutcome") instanceof Map<?, ?> o
                            ? (Map<String, Object>) o : Map.of();
                    decisions.outcome(tenantId, outcome.get("decisionId") == null ? null
                            : String.valueOf(outcome.get("decisionId")), String.valueOf(outcome.get("outcome")),
                            outcome.get("value"), outcome.get("observedAt"));
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable decision event: {}", e.getMessage());
        }
    }
}
