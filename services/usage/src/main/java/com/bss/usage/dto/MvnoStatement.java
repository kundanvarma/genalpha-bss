package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** One MVNO's statement — the machine face an external MVNO's BSS pulls to reconcile. */
@JsonPropertyOrder({"@type", "mvnoPartyId", "mvnoName", "periodStart", "line", "totalOwed", "currency"})
public record MvnoStatement(@JsonProperty("@type") String type, String mvnoPartyId, String mvnoName,
        String periodStart, List<ProviderLine> line, BigDecimal totalOwed, String currency) {
}
