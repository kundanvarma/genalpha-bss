package com.bss.stock.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox to Kafka in creation order. A row is deleted only after
 * the broker acknowledges the send; on any failure the batch stops (order
 * preserved) and the next tick retries. Delivery is therefore at-least-once —
 * a crash between send and delete produces a duplicate, never a loss;
 * consumers deduplicate by eventId (the Kafka message key).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, Object> kafkaTemplate,
            String topic, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${bss.events.relay-interval-ms:2000}")
    @Transactional
    public void flush() {
        List<OutboxEvent> batch = outbox.findTop100ByOrderByCreatedAtAsc();
        for (OutboxEvent row : batch) {
            try {
                DomainEvent event = objectMapper.readValue(row.getPayload(), DomainEvent.class);
                kafkaTemplate.send(topic, event.eventId(), event).get(5, TimeUnit.SECONDS);
                outbox.delete(row);
                meterRegistry.counter("bss.events.published", "event_type", row.getEventType()).increment();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                meterRegistry.counter("bss.events.failed", "event_type", row.getEventType()).increment();
                log.warn("outbox relay could not publish {} {}: {} — will retry",
                        row.getEventType(), row.getId(), e.getMessage());
                break;
            }
        }
    }
}
