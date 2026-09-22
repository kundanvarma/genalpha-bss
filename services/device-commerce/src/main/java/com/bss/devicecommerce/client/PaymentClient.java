package com.bss.devicecommerce.client;

import com.fasterxml.jackson.databind.JsonNode;
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
 * failing the business change that earned it. The payment component's
 * documents are foreign: they come back as trees, never re-shaped here.
 */
@Component
public class PaymentClient {

    private final RestClient rest;

    public PaymentClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-base-url:http://localhost:8087}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** The TMF676 payment as the payment component holds it, or null when it does not exist / payment is unreachable. */
    public JsonNode payment(String paymentId) {
        try {
            return rest.get().uri("/tmf-api/paymentManagement/v4/payment/{id}", paymentId)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Refund (partial or full) against a captured payment; null on failure. */
    public String refund(String paymentId, BigDecimal amount, String reason) {
        try {
            JsonNode result = rest.post()
                    .uri("/tmf-api/paymentManagement/v4/payment/{id}/refund", paymentId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("amount", Map.of("value", amount), "reason", reason))
                    .retrieve().body(JsonNode.class);
            if (result == null) {
                return null;
            }
            for (String key : new String[] {"refundRef", "settlementRef", "id"}) {
                if (result.hasNonNull(key)) {
                    return result.get(key).asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
