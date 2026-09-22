package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * The checkout chooser's answer for one financing model: monthly and TOTAL
 * cost before signing, who holds title, and the model's own sentence. The
 * bank adds its flat early-settlement fee; the other models leave it off.
 * One record for every driver behind {@code FinancingProvider}.
 */
@JsonPropertyOrder({"financingModel", "monthlyAmount", "totalCostOfOwnership", "titleHolder",
        "earlySettlementFee", "note"})
public record FinancingQuote(String financingModel, BigDecimal monthlyAmount, BigDecimal totalCostOfOwnership,
        String titleHolder, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal earlySettlementFee,
        String note) {

    public static FinancingQuote of(String financingModel, BigDecimal monthlyAmount,
            BigDecimal totalCostOfOwnership, String titleHolder, String note) {
        return new FinancingQuote(financingModel, monthlyAmount, totalCostOfOwnership, titleHolder, null, note);
    }
}
