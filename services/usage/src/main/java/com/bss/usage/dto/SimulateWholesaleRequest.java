package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** POST /simulateWholesale: the hypothetical rate card, per usage type. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulateWholesaleRequest(List<ProposedRate> rateCard) {

    public static final SimulateWholesaleRequest EMPTY = new SimulateWholesaleRequest(List.of());

    public List<ProposedRate> rates() {
        return rateCard == null ? List.of() : rateCard;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposedRate(String usageSpecName, BigDecimal wholesaleRate) {
    }
}
