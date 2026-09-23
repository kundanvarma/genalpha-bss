package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Money back: how much (default = everything still refundable) and why. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequest(
        @JsonProperty("amount") MoneyDto amount,
        @JsonProperty("reason") String reason) {

    public static final RefundRequest EMPTY = new RefundRequest(null, null);
}
