package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One row of the checkout picker: a method this tenant offers, and whether it redirects. */
@JsonPropertyOrder({"method", "redirect"})
public record PaymentMethodOption(
        @JsonProperty("method") String method,
        @JsonProperty("redirect") boolean redirect) {
}
