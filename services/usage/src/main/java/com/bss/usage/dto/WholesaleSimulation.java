package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The negotiation twin: the period's real CDRs replayed against a proposed rate card, read-only. */
@JsonPropertyOrder({"@type", "periodStart", "periodEnd", "line", "currentTotal", "proposedTotal", "delta", "currency",
        "assumptions"})
public record WholesaleSimulation(@JsonProperty("@type") String type, String periodStart, String periodEnd,
        List<Line> line, BigDecimal currentTotal, BigDecimal proposedTotal, BigDecimal delta,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency, List<String> assumptions) {

    @JsonPropertyOrder({"usageSpecName", "units", "unit", "currentRate", "proposedRate", "currentCost", "proposedCost",
            "delta"})
    public record Line(String usageSpecName, BigDecimal units, String unit, BigDecimal currentRate,
            BigDecimal proposedRate, BigDecimal currentCost, BigDecimal proposedCost, BigDecimal delta) {
    }
}
