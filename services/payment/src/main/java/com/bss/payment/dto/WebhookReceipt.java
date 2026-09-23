package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a PSP webhook confirmed. */
@JsonPropertyOrder({"provider", "sessionId", "paymentId", "status"})
public record WebhookReceipt(
        @JsonProperty("provider") String provider,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("paymentId") String paymentId,
        @JsonProperty("status") String status) {
}
