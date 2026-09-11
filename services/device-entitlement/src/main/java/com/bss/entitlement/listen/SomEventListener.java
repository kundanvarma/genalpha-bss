package com.bss.entitlement.listen;

import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.security.TenantContext;
import com.bss.entitlement.service.SubscriberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The line's state follows the orchestrator: a suspended line loses its
 * entitlements until resumed, a terminated line loses them for good (and its
 * device tokens). Events ride {@code bss.som.events}; the service id is the
 * join key to the IMSI binding.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class SomEventListener {

    private static final Logger log = LoggerFactory.getLogger(SomEventListener.class);

    private final SubscriberService subscribers;
    private final ObjectMapper objectMapper;

    public SomEventListener(SubscriberService subscribers, ObjectMapper objectMapper) {
        this.subscribers = subscribers;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.entitlement.som-topic:bss.som.events}",
            groupId = "device-entitlement-som", autoStartup = "${bss.events.enabled:true}")
    public void onSomEvent(String message) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(message, Map.class);
            String type = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? null : String.valueOf(envelope.get("tenantId"));
            Object payload = envelope.get("event") != null ? envelope.get("event") : envelope.get("payload");
            if (tenantId == null || !(payload instanceof Map<?, ?> p)) {
                return;
            }
            if ("SimReplacedEvent".equals(type)) {
                // the orchestrator swapped the line's SIM: an eSIM transfer we asked for
                // completes; any other replacement was re-bound by the orchestrator itself
                Object sim = p.get("sim") instanceof Map<?, ?> m ? m : p;
                if (sim instanceof Map<?, ?> s && s.get("serviceId") != null) {
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        subscribers.simReplaced(tenantId, String.valueOf(s.get("serviceId")),
                                s.get("reason") == null ? null : String.valueOf(s.get("reason")),
                                s.get("transferId") == null ? null : String.valueOf(s.get("transferId")));
                    }
                }
                return;
            }
            String status = switch (type) {
                case "ServiceSuspendedEvent" -> EntitlementSubscriber.SUSPENDED;
                case "ServiceResumedEvent" -> EntitlementSubscriber.ACTIVE;
                case "ServiceTerminatedEvent" -> EntitlementSubscriber.TERMINATED;
                default -> null;
            };
            if (status == null) {
                return;
            }
            String serviceId = null;
            Object service = p.get("service") != null ? p.get("service") : p;
            if (service instanceof Map<?, ?> s && s.get("id") != null) {
                serviceId = String.valueOf(s.get("id"));
            }
            if (serviceId == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                subscribers.lineState(tenantId, serviceId, status);
            }
        } catch (Exception e) {
            log.warn("entitlement: could not apply SOM event ({})", e.getMessage());
        }
    }
}
