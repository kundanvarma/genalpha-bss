package com.bss.paymentmethod.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What a saved method shows a human, plus the opaque vault token when the
 * caller is the payment service. A PAN never enters this record — there is
 * no field that could hold one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"brand", "lastFourDigits", "expiry", "token"})
public record CardDetails(String brand, String lastFourDigits, String expiry, String token) {

    public static CardDetails presentation(String brand, String lastFour, String expiry) {
        return new CardDetails(brand, lastFour, expiry, null);
    }

    public CardDetails withToken(String vaultToken) {
        return new CardDetails(brand, lastFourDigits, expiry, vaultToken);
    }
}
