package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Upsert one residual row by (deviceRef, ageMonths). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResidualRequest(String deviceRef, Integer ageMonths, BigDecimal baseValue, String currency) {
}
