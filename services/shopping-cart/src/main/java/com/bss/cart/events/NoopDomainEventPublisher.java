package com.bss.cart.events;

/**
 * Discards events. Active when bss.events.enabled=false (e.g. the test
 * profile), so API tests do not pay Kafka connection timeouts.
 */
public class NoopDomainEventPublisher implements DomainEventPublisher {

    @Override
    public void publish(String eventType, String resourceKey, Object resource) {
        // intentionally empty
    }

    @Override
    public void publish(String eventType, String resourceKey, Object resource, String tenantId) {
        // intentionally empty
    }
}
