package com.bss.ontology.service;

import com.bss.ontology.client.OntologyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The receipt: every executed action is a decision of the BSS, logged in the
 * same shape the campaign and intelligence deciders use (DecisionRecordedEvent,
 * TMF688-style envelope), so insight's decision log shows it beside them with
 * the same six-sentence receipt. Best effort: a receipt that cannot be sent is
 * logged, never a reason to undo a completed order.
 */
@Component
public class ReceiptPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPublisher.class);

    private final ObjectProvider<KafkaTemplate<String, String>> kafka;
    private final ObjectMapper json;
    private final OntologyProperties props;

    public ReceiptPublisher(ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper json, OntologyProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.props = props;
    }

    public void decision(String tenant, Map<String, Object> decision) {
        send(tenant, "DecisionRecordedEvent", Map.of("decision", decision));
    }

    public void outcome(String tenant, String decisionId, String outcome, Object value) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("decisionId", decisionId);
        o.put("outcome", outcome);
        if (value != null) {
            o.put("value", value);
        }
        o.put("observedAt", OffsetDateTime.now().toString());
        o.put("@type", "DecisionOutcome");
        send(tenant, "DecisionOutcomeEvent", Map.of("decisionOutcome", o));
    }

    private void send(String tenant, String type, Map<String, Object> event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        envelope.put("eventId", id);
        envelope.put("eventTime", OffsetDateTime.now().toString());
        envelope.put("eventType", type);
        envelope.put("tenantId", tenant);
        envelope.put("event", event);
        KafkaTemplate<String, String> template = kafka.getIfAvailable();
        if (template == null) {
            log.warn("no Kafka: {} for tenant {} not published", type, tenant);
            return;
        }
        try {
            template.send(props.getReceiptTopic(), id, json.writeValueAsString(envelope))
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            log.warn("{} not published: {}", type, err.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("{} not published: {}", type, e.getMessage());
        }
    }
}
