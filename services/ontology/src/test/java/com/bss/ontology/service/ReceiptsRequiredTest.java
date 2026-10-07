package com.bss.ontology.service;

import com.bss.ontology.client.OntologyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The hole this closes was not hypothetical. During the Spring Boot 4 migration
 * the ontology service was missing one per-technology autoconfiguration module,
 * so no KafkaTemplate bean existed. The publisher logged at WARN and returned,
 * and every receipt for every governed action silently went unpublished — while
 * the service reported healthy and its health check passed.
 *
 * A missing KafkaTemplate is a broken deployment, not a transient fault, and a
 * service that writes the evidence for the actions it executes must not run
 * without the ability to write it. These tests hold that shut, and hold the
 * escape hatch honest.
 */
class ReceiptsRequiredTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaTemplate<String, String>> noKafka() {
        ObjectProvider<KafkaTemplate<String, String>> p = mock(ObjectProvider.class);
        given(p.getIfAvailable()).willReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaTemplate<String, String>> kafkaThatFails() {
        ObjectProvider<KafkaTemplate<String, String>> p = mock(ObjectProvider.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        given(template.send(anyString(), anyString(), anyString()))
                .willThrow(new IllegalStateException("broker is gone"));
        given(p.getIfAvailable()).willReturn(template);
        return p;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> meterProvider() {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        given(p.getIfAvailable()).willReturn(meters);
        return p;
    }

    private ReceiptPublisher publisher(ObjectProvider<KafkaTemplate<String, String>> kafka, boolean required) {
        OntologyProperties props = new OntologyProperties();
        props.setReceiptsRequired(required);
        return new ReceiptPublisher(kafka, json, props, meterProvider());
    }

    @Test
    void withNoKafkaTheServiceRefusesToStart() {
        ReceiptPublisher publisher = publisher(noKafka(), true);

        assertThatThrownBy(publisher::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no KafkaTemplate")
                .hasMessageContaining("must not run without it");
    }

    @Test
    void withKafkaPresentTheServiceStarts() {
        assertThatCode(publisher(kafkaThatFails(), true)::afterPropertiesSet).doesNotThrowAnyException();
    }

    /**
     * The escape hatch has to work, or someone will delete the gate instead of
     * configuring it. It is false only by deliberate choice, and it says so in
     * the log.
     */
    @Test
    void theOptOutIsHonouredAndStartsWithoutKafka() {
        assertThatCode(publisher(noKafka(), false)::afterPropertiesSet).doesNotThrowAnyException();
    }

    /**
     * A send that fails after the action completed cannot be undone, so the
     * honest response is to make it countable. This used to be a WARN and
     * nothing else.
     */
    @Test
    void aReceiptThatCannotBeSentIsCounted() {
        ReceiptPublisher publisher = publisher(kafkaThatFails(), true);

        publisher.decision("genalpha", Map.of("decisionId", "d-1"));

        assertThat(meters.counter("bss.ontology.receipts.dropped").count()).isEqualTo(1.0);
    }

    @Test
    void aDroppedReceiptWithNoKafkaIsAlsoCounted() {
        ReceiptPublisher publisher = publisher(noKafka(), false);

        publisher.outcome("genalpha", "d-2", "completed", "order-9");

        assertThat(meters.counter("bss.ontology.receipts.dropped").count()).isEqualTo(1.0);
    }
}
