package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/**
 * Early termination without a swap: the agreement plus the ETF collected and
 * the subsidy still on the book (revenue recovers the unearned part). A
 * replay on an already-settled agreement carries no ETF facts — the
 * agreement alone, as before.
 */
@JsonPropertyOrder({"agreement", "etfAmount", "remainingSubsidy"})
public record SettleReceipt(
        @JsonUnwrapped DeviceAgreementView agreement,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal etfAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal remainingSubsidy) {

    public static SettleReceipt unchanged(DeviceAgreementView agreement) {
        return new SettleReceipt(agreement, null, null);
    }
}
