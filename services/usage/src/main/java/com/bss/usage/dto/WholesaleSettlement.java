package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The MVNO's settlement statement with reconciliation: rated units vs the CDRs now. */
@JsonPropertyOrder({"@type", "periodType", "periodStart", "hostPartyId", "line", "totalOwed", "currency", "reconciled"})
public record WholesaleSettlement(@JsonProperty("@type") String type, String periodType, String periodStart,
        String hostPartyId, List<Line> line, BigDecimal totalOwed, String currency, boolean reconciled) {

    @JsonPropertyOrder({"usageSpecName", "ratedUnits", "liveUnits", "unit", "wholesaleRate", "amount", "currency",
            "reconciled"})
    public record Line(String usageSpecName, BigDecimal ratedUnits, BigDecimal liveUnits, String unit,
            BigDecimal wholesaleRate, BigDecimal amount, String currency, boolean reconciled) {
    }
}
