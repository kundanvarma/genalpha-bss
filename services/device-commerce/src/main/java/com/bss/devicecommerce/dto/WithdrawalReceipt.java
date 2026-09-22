package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;

/**
 * The answer to opening a withdrawal: the case, then the agreement facts
 * revenue reverses against — written whole when present, {@code subsidyAmount}
 * null included (an unsubsidised agreement says so) — and a note when no
 * PSP payment existed to refund through. A replay (one withdrawal per
 * agreement) returns the case alone, as before.
 */
@JsonPropertyOrder({"withdrawal", "agreement", "note"})
public record WithdrawalReceipt(
        @JsonUnwrapped WithdrawalCaseView withdrawal,
        @JsonUnwrapped AgreementFacts agreement,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note) {

    /** What the withdrawn agreement carried: nulls are written, the block is absent only on a replay. */
    @JsonPropertyOrder({"subsidyAmount", "financingModel", "currency"})
    public record AgreementFacts(BigDecimal subsidyAmount, String financingModel, String currency) {
    }

    public static WithdrawalReceipt unchanged(WithdrawalCaseView withdrawal) {
        return new WithdrawalReceipt(withdrawal, null, null);
    }
}
