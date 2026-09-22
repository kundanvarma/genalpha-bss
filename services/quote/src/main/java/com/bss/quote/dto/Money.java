package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * A priced amount with its period: a line's unit price, a quote's monthly
 * or one-time total. The value keeps the scale it was computed or stored
 * with — never re-scaled here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"value", "unit", "period"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record Money(BigDecimal value, String unit, String period) {

    public static Money monthly(BigDecimal value, String unit) {
        return new Money(value, unit, "month");
    }

    public static Money oneTime(BigDecimal value, String unit) {
        return new Money(value, unit, "oneTime");
    }
}
