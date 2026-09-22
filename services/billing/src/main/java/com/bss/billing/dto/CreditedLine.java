package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * One rate line being reversed, with the amount credited on it. Stored on
 * the note as JSON; read back with the amount as a decimal.
 */
@JsonPropertyOrder({"id", "name", "amount"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditedLine(String id, String name, BigDecimal amount) {
}
