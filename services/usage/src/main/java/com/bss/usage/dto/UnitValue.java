package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A metered quantity: {value, units} — an allowance, a synthesised usage characteristic. */
@JsonPropertyOrder({"value", "units"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnitValue(BigDecimal value, String units) {
}
