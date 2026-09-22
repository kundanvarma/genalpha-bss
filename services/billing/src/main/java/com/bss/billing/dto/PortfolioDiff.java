package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** Two portfolios, one pricing engine, the difference by name. */
@JsonPropertyOrder({"@type", "tenantA", "tenantB", "matched", "changed", "onlyInA", "onlyInB", "portfolioMonthlyA",
        "portfolioMonthlyB", "portfolioDelta", "assumptions"})
public record PortfolioDiff(@JsonProperty("@type") String type, String tenantA, String tenantB, int matched,
        List<OfferingDelta> changed, List<String> onlyInA, List<String> onlyInB, BigDecimal portfolioMonthlyA,
        BigDecimal portfolioMonthlyB, BigDecimal portfolioDelta, List<String> assumptions) {

    @JsonPropertyOrder({"name", "monthlyA", "monthlyB", "delta"})
    public record OfferingDelta(String name, BigDecimal monthlyA, BigDecimal monthlyB, BigDecimal delta) {
    }
}
