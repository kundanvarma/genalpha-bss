package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The host's consolidated book for a period: per MVNO, what each owes. */
@JsonPropertyOrder({"@type", "periodType", "periodStart", "mvno", "totalRevenue", "currency"})
public record ProviderSettlement(@JsonProperty("@type") String type, String periodType, String periodStart,
        List<Mvno> mvno, BigDecimal totalRevenue, String currency) {

    @JsonPropertyOrder({"mvnoPartyId", "mvnoName", "line", "total"})
    public record Mvno(String mvnoPartyId, String mvnoName, List<ProviderLine> line, BigDecimal total) {
    }
}
