package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * POST prospectSimulation: PUBLIC input only — the prospect's price list and
 * an assumed base mix. No data of theirs is ever in this body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectSimulationRequest(String id, String name, String currency, String locale, String category,
        List<PriceRow> priceList, List<MixRow> baseMix) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceRow(String offeringName, BigDecimal monthly) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MixRow(String offeringName, Integer subscribers) {
    }
}
