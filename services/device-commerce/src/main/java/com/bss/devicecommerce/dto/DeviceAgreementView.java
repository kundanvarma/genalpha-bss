package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A device financing agreement as every channel reads it: the schedule
 * (instalments paid of term), the paid share and remaining principal, the
 * financier facts and the upgrade rule. Money is the entity's BigDecimal as
 * stored. Dates are ISO strings, as the map wrote them.
 */
@JsonPropertyOrder({"id", "href", "financingModel", "status", "subscriptionRef", "orderRef", "device",
        "principal", "termMonths", "monthlyAmount", "totalCostOfOwnership", "currency", "installmentsPaid",
        "paidSharePct", "remainingPrincipal", "financierRef", "externalAgreementNo", "titleHolder",
        "upgradeRule", "residualValue", "subsidyAmount", "shippingCost", "paymentRef", "payoutReceivedAt",
        "deliveredAt", "tradeInDelta", "relatedParty", "@type"})
public record DeviceAgreementView(
        String id,
        String href,
        String financingModel,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String subscriptionRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String orderRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) DeviceRef device,
        BigDecimal principal,
        int termMonths,
        BigDecimal monthlyAmount,
        BigDecimal totalCostOfOwnership,
        String currency,
        int installmentsPaid,
        BigDecimal paidSharePct,
        BigDecimal remainingPrincipal,
        @JsonInclude(JsonInclude.Include.NON_NULL) String financierRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String externalAgreementNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) String titleHolder,
        @JsonInclude(JsonInclude.Include.NON_NULL) UpgradeRule upgradeRule,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal residualValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal subsidyAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal shippingCost,
        @JsonInclude(JsonInclude.Include.NON_NULL) String paymentRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String payoutReceivedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deliveredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal tradeInDelta,
        List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) {

    public static final String TYPE = "DeviceAgreement";
}
