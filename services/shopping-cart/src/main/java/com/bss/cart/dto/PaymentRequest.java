package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * The TMF676 payment this service posts on the agent's behalf: the due-now
 * total against the delegated payment token, correlated to the session.
 */
@JsonPropertyOrder({"description", "amount", "paymentMethod", "correlatorId"})
public record PaymentRequest(String description, Money amount, PaymentMethod paymentMethod, String correlatorId) {

    @JsonPropertyOrder({"unit", "value"})
    public record Money(String unit, BigDecimal value) {
    }

    /** The ACP delegated payment token IS the PSP-scoped token: it authorizes exactly this cart, this amount. */
    @JsonPropertyOrder({"@type", "token"})
    public record PaymentMethod(@JsonProperty("@type") String type, String token) {

        public static PaymentMethod sharedPaymentToken(String token) {
            return new PaymentMethod("sharedPaymentToken", token);
        }
    }
}
