package com.bss.assurance.events;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * TMF688-style event envelope. The payload sits under {@code event}, keyed by
 * the TMF resource name, e.g. {"event": {"productOrder": {...}}}.
 */
public record DomainEvent(
        String eventId,
        OffsetDateTime eventTime,
        String eventType,
        String tenantId,
        Map<String, Object> event) {
}
