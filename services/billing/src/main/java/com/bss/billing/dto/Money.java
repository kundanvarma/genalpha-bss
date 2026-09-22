package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * TMF Money: {unit, value}. The value is the entity's BigDecimal as stored —
 * a bill due of 0.00 stays 0.00 on the wire, never 0 or 0.0.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"unit", "value"})
public record Money(String unit, BigDecimal value) {
}
