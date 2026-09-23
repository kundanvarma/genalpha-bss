package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** TMF Money as the remittance door receives it. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"unit", "value"})
public record MoneyRef(@JsonProperty("unit") String unit, @JsonProperty("value") BigDecimal value) {
}
