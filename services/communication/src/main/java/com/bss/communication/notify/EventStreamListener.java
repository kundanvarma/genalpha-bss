package com.bss.communication.notify;

import com.bss.communication.security.TenantContext;
import com.bss.communication.service.CommunicationMessageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Where the event architecture becomes visible: this listener drinks from the
 * same topics the outboxes fill and mints customer notifications from the
 * events that matter. Delivery upstream is at-least-once, so minting is
 * idempotent on the source eventId. A malformed record is logged and skipped
 * — one bad event must not stall the stream.
 */
@Component
@ConditionalOnProperty(name = "bss.notify.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class EventStreamListener {

    private static final Logger log = LoggerFactory.getLogger(EventStreamListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final EventNotificationMapper mapper;
    private final CommunicationMessageService messages;
    private final ObjectMapper objectMapper;

    public EventStreamListener(EventNotificationMapper mapper, CommunicationMessageService messages,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.notify.topics}'.split(',')}", groupId = "communication")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventId = String.valueOf(envelope.get("eventId"));
            String eventType = String.valueOf(envelope.get("eventType"));
            // Envelope tenant keeps the notification inside the tenant that
            // produced the event; pre-tenancy events carry none and land in
            // the default tenant.
            String tenantId = envelope.get("tenantId") == null ? null : String.valueOf(envelope.get("tenantId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            var notifications = mapper.map(eventType, event);
            for (int i = 0; i < notifications.size(); i++) {
                // Act as the producing tenant so the row-level policies admit
                // the insert; pre-tenancy envelopes fall back to the default.
                // One event may speak to several people (a gift has two ends)
                // — each recipient gets their own idempotency key.
                String mintId = i == 0 ? eventId : eventId + "#" + i;
                try (TenantContext ignored = TenantContext.actAs(
                        tenantId != null ? tenantId : "genalpha")) {
                    messages.mint(mintId, eventType, tenantId, notifications.get(i));
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable event: {}", e.getMessage());
        }
    }
}
