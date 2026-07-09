package com.bss.catalog.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes events to Kafka after the surrounding transaction commits, so a
 * rolled-back change never produces a phantom event. Publishing is
 * best-effort: broker failures are logged, never propagated to the caller
 * (producer max.block.ms is capped in configuration to keep the worst case
 * short). At-least-once delivery would require a transactional outbox, which
 * is a documented future step.
 */
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public KafkaDomainEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(String eventType, String resourceKey, Object resource) {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(),
                OffsetDateTime.now(),
                eventType,
                Map.of(resourceKey, resource));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(DomainEvent event) {
        try {
            kafkaTemplate.send(topic, event.eventId(), event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("failed to publish {} {} to {}: {}",
                            event.eventType(), event.eventId(), topic, ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("failed to publish {} {} to {}: {}",
                    event.eventType(), event.eventId(), topic, e.getMessage());
        }
    }
}
