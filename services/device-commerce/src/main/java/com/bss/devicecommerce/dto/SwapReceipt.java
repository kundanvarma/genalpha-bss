package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/**
 * The upgrade/swap saga's answer: the swapped agreement, the model-specific
 * settlement, the trade-in that stood behind it and the subsidy still on the
 * book. A replay of a completed swap returns the agreement alone, as before.
 */
@JsonPropertyOrder({"agreement", "settlement", "tradeInValuationId", "remainingSubsidy"})
public record SwapReceipt(
        @JsonUnwrapped DeviceAgreementView agreement,
        @JsonInclude(JsonInclude.Include.NON_NULL) FinancingSettlement settlement,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tradeInValuationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal remainingSubsidy) {

    public static SwapReceipt unchanged(DeviceAgreementView agreement) {
        return new SwapReceipt(agreement, null, null, null);
    }
}
