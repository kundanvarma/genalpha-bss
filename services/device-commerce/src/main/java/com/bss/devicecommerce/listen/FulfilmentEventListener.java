package com.bss.devicecommerce.listen;

import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The withdrawal clock's ear: the same parcel-DELIVERED event that
 * activates a physical SIM starts the 14 days here. The shipping order
 * carries the product order id; agreements bound to that order get their
 * deliveredAt stamped (first delivery wins — at-least-once is fine).
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class FulfilmentEventListener {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final DeviceAgreementRepository agreements;
    private final ObjectMapper objectMapper;

    public FulfilmentEventListener(DeviceAgreementRepository agreements, ObjectMapper objectMapper) {
        this.agreements = agreements;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.device.fulfilment-topic:bss.fulfilment.events}",
            groupId = "device-commerce")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"ShippingOrderStateChangeEvent".equals(envelope.get("eventType"))) {
                return;
            }
            Map<String, Object> shippingOrder = resource(envelope, "shippingOrder");
            if (!"delivered".equals(shippingOrder.get("state"))
                    || shippingOrder.get("productOrderId") == null) {
                return;
            }
            String orderRef = String.valueOf(shippingOrder.get("productOrderId"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                for (DeviceAgreement a : agreements.findByTenantIdAndOrderRef(tenantId, orderRef)) {
                    if (a.getDeliveredAt() == null) {
                        a.setDeliveredAt(OffsetDateTime.now());
                        a.setLastUpdate(OffsetDateTime.now());
                        agreements.save(a);
                        log.info("device agreement {}: withdrawal clock started (order {} delivered)",
                                a.getId(), orderRef);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("device-commerce: skipping unprocessable fulfilment event: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resource(Map<String, Object> envelope, String key) {
        Object event = envelope.get("event");
        if (event instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> resource) {
            return (Map<String, Object>) resource;
        }
        return Map.of();
    }
}
