package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * The model-specific facts of settling an agreement on a swap. Operator
 * book: the remainder writes off against the graded trade-in (a shortfall
 * is the program's cost, a surplus the customer's credit). Bank: the early
 * settlement quote is paid, the trade-in covers what it can. BNPL: the
 * provider settles with the customer; the trade-in credits the new
 * purchase. One record, the key order is the union of the three paths, and
 * each path writes only its own facts — revenue reads {@code remainingPrincipal}
 * and {@code tradeInValue} from the operator-book path.
 */
@JsonPropertyOrder({"financingModel", "remainingPrincipal", "settlementAmount", "settlementDelegated", "provider",
        "providerSettlementStatus", "tradeInValue", "writeOff", "shortfall", "customerCredit",
        "externalAgreementNo", "note"})
public record FinancingSettlement(
        String financingModel,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal remainingPrincipal,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal settlementAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean settlementDelegated,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String providerSettlementStatus,
        BigDecimal tradeInValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal writeOff,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal shortfall,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal customerCredit,
        @JsonInclude(JsonInclude.Include.NON_NULL) String externalAgreementNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note) {

    /** Operator book: remaining instalments written off against the graded device. */
    public static FinancingSettlement writeOff(String model, BigDecimal remainingPrincipal, BigDecimal tradeInValue,
            BigDecimal writeOff, BigDecimal customerCredit) {
        return new FinancingSettlement(model, remainingPrincipal, null, null, null, null, tradeInValue, writeOff,
                null, customerCredit, null, null);
    }

    /** Partner bank: its early-settlement quote paid, the trade-in toward it. */
    public static FinancingSettlement bank(String model, BigDecimal settlementAmount, BigDecimal tradeInValue,
            BigDecimal shortfall, BigDecimal customerCredit, String externalAgreementNo, String note) {
        return new FinancingSettlement(model, null, settlementAmount, null, null, null, tradeInValue, null,
                shortfall, customerCredit, externalAgreementNo, note);
    }

    /** BNPL: settlement is the provider's; the trade-in credits the new purchase. */
    public static FinancingSettlement delegated(String model, String provider, String providerSettlementStatus,
            BigDecimal tradeInValue, String note) {
        return new FinancingSettlement(model, null, null, true, provider, providerSettlementStatus, tradeInValue,
                null, null, null, null, note);
    }
}
