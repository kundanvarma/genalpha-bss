package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** {value, unit} — an amount at the scale it was stored. */
@JsonPropertyOrder({"value", "unit"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record Money(BigDecimal value, String unit) {
}
