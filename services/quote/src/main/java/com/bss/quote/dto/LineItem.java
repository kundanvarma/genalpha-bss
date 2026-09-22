package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * A line as a caller (the opportunity, the CPQ validate endpoint, guided
 * selling) proposes it: an offering, how many, at what list price, recurring
 * or one-off. Quantity defaults to one, price to zero, recurring to true.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LineItem(String offeringId, String offeringName, Integer quantity, BigDecimal unitPrice,
        Boolean recurring) {

    public int quantityOrOne() {
        return quantity == null ? 1 : quantity;
    }

    public BigDecimal unitPriceOrZero() {
        return unitPrice == null ? BigDecimal.ZERO : unitPrice;
    }

    public boolean isRecurring() {
        return !Boolean.FALSE.equals(recurring);
    }
}
