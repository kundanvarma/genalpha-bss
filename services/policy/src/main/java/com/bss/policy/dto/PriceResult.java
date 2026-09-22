package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The pricing rules applied to a subtotal: every matching rule's adjustment
 * in priority order and the compounded total. The guest's preview is the
 * same record labelled {@code indicative: true}.
 */
@JsonPropertyOrder({"basePrice", "adjustments", "total", "indicative"})
public record PriceResult(BigDecimal basePrice, List<Adjustment> adjustments, BigDecimal total,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean indicative) {

    /** One rule's contribution: its identity, the label the shopper sees, and the delta it made. */
    @JsonPropertyOrder({"ruleId", "ruleName", "label", "type", "value", "amount"})
    public record Adjustment(String ruleId, String ruleName, String label, String type, BigDecimal value,
            BigDecimal amount) {
    }

    public static PriceResult of(BigDecimal basePrice, List<Adjustment> adjustments, BigDecimal total) {
        return new PriceResult(basePrice, adjustments, total, null);
    }

    public PriceResult asIndicative() {
        return new PriceResult(basePrice, adjustments, total, true);
    }
}
