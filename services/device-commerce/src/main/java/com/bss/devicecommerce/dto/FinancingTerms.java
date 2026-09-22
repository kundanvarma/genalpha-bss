package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The checkout chooser's question: how much, over how many months, on which
 * model (the operator's book when unsaid). The principal is echoed as sent
 * — the quote's total cost of ownership is the number the shopper typed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinancingTerms(BigDecimal principal, Integer termMonths, String financingModel) {
}
