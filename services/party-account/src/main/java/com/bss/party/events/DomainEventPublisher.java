package com.bss.party.events;

/**
 * Publishes TMF688 domain events. Implementations must be safe to call inside
 * a database transaction: the event must only become visible after commit, and
 * a broker failure must never fail the API request that caused the event.
 */
public interface DomainEventPublisher {

    void publish(String eventType, String resourceKey, Object resource);
}
