package com.bss.som.events;

import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional-outbox publisher: appends the event to the outbox table
 * inside the caller's transaction. The event becomes durable exactly when
 * the business change commits — a rollback discards both, a commit loses
 * neither. {@link OutboxRelay} delivers to Kafka asynchronously.
 */
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final TenantScope tenantScope;

    public OutboxDomainEventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper,
            TenantScope tenantScope) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.tenantScope = tenantScope;
    }

    @Override
    public void publish(String eventType, String resourceKey, Object resource) {
        publish(eventType, resourceKey, resource, tenantScope.currentTenantId());
    }

    @Override
    public void publish(String eventType, String resourceKey, Object resource, String tenantId) {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(),
                OffsetDateTime.now(),
                eventType,
                tenantId,
                Map.of(resourceKey, resource));
        OutboxEvent row = new OutboxEvent();
        row.setId(event.eventId());
        row.setEventType(eventType);
        row.setPayload(write(event));
        row.setCreatedAt(OffsetDateTime.now());
        outbox.save(row);
    }

    private String write(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable domain event", e);
        }
    }
}
