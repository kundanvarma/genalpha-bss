package com.bss.policy.events;

/**
 * Publishes TMF688 domain events. Implementations must be safe to call inside
 * a database transaction: the event must only become visible after commit, and
 * a broker failure must never fail the API request that caused the event.
 */
public interface DomainEventPublisher {

    void publish(String eventType, String resourceKey, Object resource);

    /**
     * Same, but with an explicit tenant — for system jobs that act on rows
     * outside any request context (sweepers, relays) where the tenant comes
     * from the row, not the caller.
     */
    void publish(String eventType, String resourceKey, Object resource, String tenantId);
}
