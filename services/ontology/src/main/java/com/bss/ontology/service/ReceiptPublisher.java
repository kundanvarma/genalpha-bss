package com.bss.ontology.service;

import com.bss.ontology.client.OntologyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
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
 * the same six-sentence receipt.
 *
 * <h2>Why this class refuses to start rather than warn</h2>
 *
 * It used to open with "best effort: a receipt that cannot be sent is logged,
 * never a reason to undo a completed order". Half of that is still true and
 * still right — a broker blip must not unwind an order that has already been
 * placed downstream. The other half was a hole, and it opened:
 *
 * <p>During the Spring Boot 4 migration this service was missing one
 * per-technology autoconfiguration module, so there was no KafkaTemplate bean
 * at all. {@code getIfAvailable()} returned null, this method logged at WARN
 * and returned, and <b>every receipt for every governed action silently went
 * unpublished</b>. The service reported healthy. Its health check passed.
 * Nothing failed. It took a deliberate audit to find, and the two services
 * with the same gap that crashed loudly were fixed in minutes — precisely
 * because they failed closed.
 *
 * <p>Evidence that can silently not exist is worse than no evidence, because
 * people believe it. A missing KafkaTemplate is not a transient fault, it is a
 * broken deployment, and a service that cannot write evidence must not accept
 * governed actions. So that case now fails at startup, where blocking is clean
 * — before a single action has run — instead of per-call, after the fact.
 *
 * <h2>Honest limits</h2>
 *
 * A send that fails <i>after</i> the action completed still cannot be undone;
 * it is logged at ERROR and counted on {@code bss.ontology.receipts.dropped}
 * so it is visible to an alert rather than buried in a warning. Making those
 * durable needs a receipt store, which does not exist yet (issue #234). Until
 * it does, the durable record of a governed action is Kafka retention plus
 * whatever a subscriber kept, and this class cannot honestly promise more.
 */
@Component
public class ReceiptPublisher implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPublisher.class);

    private final ObjectProvider<KafkaTemplate<String, String>> kafka;
    private final ObjectMapper json;
    private final OntologyProperties props;
    private final Counter dropped;

    public ReceiptPublisher(ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper json,
            OntologyProperties props, ObjectProvider<MeterRegistry> meters) {
        this.kafka = kafka;
        this.json = json;
        this.props = props;
        MeterRegistry registry = meters.getIfAvailable();
        this.dropped = registry == null ? null : Counter.builder("bss.ontology.receipts.dropped")
                .description("governed-action receipts that could not be published")
                .register(registry);
    }

    /**
     * Refuse to start when receipts cannot be written at all. This is the gate
     * the Boot 4 incident went straight through; see the class comment.
     */
    @Override
    public void afterPropertiesSet() {
        if (!props.isReceiptsRequired()) {
            log.warn("receipts are NOT required: governed actions will run without evidence "
                    + "if Kafka is absent — ontology.receipts-required is false");
            return;
        }
        if (kafka.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "no KafkaTemplate, so no governed action could leave a receipt. This service "
                    + "writes the evidence for every action it executes and must not run without "
                    + "it. Check that spring-boot-kafka is on the classpath and spring.kafka is "
                    + "configured. To run deliberately without evidence, set "
                    + "ontology.receipts-required=false and accept that actions will not be "
                    + "provable.");
        }
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
            /* Only reachable with receipts-required=false — afterPropertiesSet
             * would have refused to start otherwise. */
            drop(type, tenant, id, "no KafkaTemplate");
            return;
        }
        try {
            template.send(props.getReceiptTopic(), id, json.writeValueAsString(envelope))
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            drop(type, tenant, id, err.getMessage());
                        }
                    });
        } catch (Exception e) {
            drop(type, tenant, id, e.getMessage());
        }
    }

    /**
     * A receipt that will not exist. ERROR, not WARN: this is missing evidence
     * for an action that already happened, and the only honest response is to
     * make it loud enough to alert on.
     */
    private void drop(String type, String tenant, String eventId, String why) {
        if (dropped != null) {
            dropped.increment();
        }
        log.error("RECEIPT LOST: {} for tenant {} (eventId {}) was not published: {} — "
                + "a governed action ran with no evidence",
                type, oneLine(tenant), eventId, oneLine(why));
    }

    /**
     * A line break in a logged value stops being part of the value and becomes
     * a log line of someone else's choosing. The tenant id arrives on a token
     * and the reason is a provider's exception message, so neither is ours —
     * and this one line is the evidence that a receipt went missing, which is
     * precisely the line worth forging. Collapsed, not dropped: a mangled
     * value still has to be readable by whoever is reading the alert.
     */
    private static String oneLine(String value) {
        // CHAINED replace(char, char), not replaceAll with a class. Both collapse
        // the line breaks; only this shape is the one CodeQL models as a
        // log-injection sanitiser, so the regex version left the alert standing
        // on a line that was already safe. Same spelling as oneLine() in
        // service-orchestration, which learned it first.
        return value == null ? null : value
                .replace('\n', '_').replace('\r', '_')
                .replace('\u0085', '_').replace('\u2028', '_').replace('\u2029', '_');
    }
}
