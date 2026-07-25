package com.bss.revenue.listen;

import com.bss.revenue.security.TenantContext;
import com.bss.revenue.service.RevenueService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Cash books ONCE, at capture: every path — card checkout, CSR payment,
 * bank giro through remittance matching — ends in a capture
 * PaymentStateChangeEvent, so that is the single booking point (the
 * recon's double-event trap: a matched bank payment also fires create
 * and remittance events; those are memo here). Refunds book on their
 * own event.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.payment-topic:bss.payment.events}", groupId = "revenue")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                if ("PaymentStateChangeEvent".equals(eventType)) {
                    Map<String, Object> payment = BillingEventListener.resource(envelope, "payment");
                    if ("captured".equals(payment.get("status")) && payment.get("id") != null) {
                        revenue.postCash(String.valueOf(payment.get("id")), "captured", payment);
                    }
                } else if ("PaymentRefundEvent".equals(eventType)) {
                    Map<String, Object> refund = BillingEventListener.resource(envelope, "refund");
                    if (!refund.isEmpty()) {
                        revenue.postRefund(refund);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable payment event: {}", e.getMessage());
        }
    }
}
