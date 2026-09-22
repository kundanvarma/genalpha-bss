package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One statement line on the provider face. */
@JsonPropertyOrder({"usageSpecName", "totalUnits", "unit", "rate", "amount", "currency"})
public record ProviderLine(String usageSpecName, BigDecimal totalUnits, String unit, BigDecimal rate,
        BigDecimal amount, String currency) {
}
