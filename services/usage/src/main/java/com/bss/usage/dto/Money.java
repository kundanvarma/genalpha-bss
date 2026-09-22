package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * TMF Money as usage writes it: {value, unit}. The value is the entity's
 * BigDecimal as stored, never re-scaled; a meter without a currency writes
 * {@code "unit": null}, as the map did.
 */
@JsonPropertyOrder({"value", "unit"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record Money(BigDecimal value, String unit) {
}
