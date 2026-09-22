package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Complete: the delegated payment token that authorizes exactly this cart. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteRequest(@JsonProperty("payment_data") PaymentData paymentData) {

    public static final CompleteRequest EMPTY = new CompleteRequest(null);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentData(String token) {
    }

    public String token() {
        return paymentData == null ? null : paymentData.token();
    }
}
