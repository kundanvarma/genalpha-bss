package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** An amount as the entity stores it — never re-scaled. */
@JsonPropertyOrder({"value", "unit"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record Money(BigDecimal value, String unit) {
}
