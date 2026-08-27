package com.bss.devicecommerce.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The money door. Device-commerce never touches a PSP itself — refunds and
 * payment validation go through the payment component's TMF676 face, as a
 * machine caller under the acting tenant's own identity. Fail-soft: a
 * refund that cannot reach the PSP records "no refundRef" instead of
 * failing the business change that earned it.
 */
@Component
public class PaymentClient {

    private final RestClient rest;

    public PaymentClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-base-url:http://localhost:8087}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** The payment, or null when it does not exist / payment is unreachable. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> payment(String paymentId) {
        try {
            return rest.get().uri("/tmf-api/paymentManagement/v4/payment/{id}", paymentId)
                    .retrieve().body(Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Refund (partial or full) against a captured payment; null on failure. */
    @SuppressWarnings("unchecked")
    public String refund(String paymentId, BigDecimal amount, String reason) {
        try {
            Map<String, Object> result = rest.post()
                    .uri("/tmf-api/paymentManagement/v4/payment/{id}/refund", paymentId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("amount", Map.of("value", amount), "reason", reason))
                    .retrieve().body(Map.class);
            if (result == null) {
                return null;
            }
            Object ref = result.getOrDefault("refundRef",
                    result.getOrDefault("settlementRef", result.get("id")));
            return ref == null ? null : String.valueOf(ref);
        } catch (Exception e) {
            return null;
        }
    }
}
