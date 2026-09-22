package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * What ending the financing early costs right now. One record for every
 * driver: the operator's book quotes remaining instalments at face value
 * (fee zero), the bank quotes remaining + its flat fee under its own
 * reference, BNPL quotes an indicative figure and says the provider owns the
 * binding one. The key order is the union of the three paths; a path writes
 * only its own facts. The service adds the agreement and currency last.
 */
@JsonPropertyOrder({"financingModel", "amount", "remainingPrincipal", "fee", "financierRef",
        "externalAgreementNo", "settlementDelegated", "provider", "note", "agreementId", "currency"})
public record EarlySettlementQuote(
        String financingModel,
        BigDecimal amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal remainingPrincipal,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal fee,
        @JsonInclude(JsonInclude.Include.NON_NULL) String financierRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String externalAgreementNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean settlementDelegated,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) String agreementId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency) {

    /** Operator book: remaining instalments at face value, no fee. */
    public static EarlySettlementQuote operatorBook(String model, BigDecimal amount, BigDecimal fee, String note) {
        return new EarlySettlementQuote(model, amount, null, fee, null, null, null, null, note, null, null);
    }

    /** Partner bank: remaining principal plus the bank's flat fee, under the bank's reference. */
    public static EarlySettlementQuote bank(String model, BigDecimal amount, BigDecimal remainingPrincipal,
            BigDecimal fee, String financierRef, String externalAgreementNo) {
        return new EarlySettlementQuote(model, amount, remainingPrincipal, fee, financierRef, externalAgreementNo,
                null, null, null, null, null);
    }

    /** BNPL: indicative only — the provider owns the schedule and quotes the binding figure. */
    public static EarlySettlementQuote delegated(String model, BigDecimal amount, String provider, String note) {
        return new EarlySettlementQuote(model, amount, null, null, null, null, true, provider, note, null, null);
    }

    /** The service's stamp: which agreement, in which currency. */
    public EarlySettlementQuote forAgreement(String agreementId, String currency) {
        return new EarlySettlementQuote(financingModel, amount, remainingPrincipal, fee, financierRef,
                externalAgreementNo, settlementDelegated, provider, note, agreementId, currency);
    }
}
