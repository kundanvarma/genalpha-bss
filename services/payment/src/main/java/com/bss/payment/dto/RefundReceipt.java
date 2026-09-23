package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a refund did. {@code reason} is written even when it is null — the map
 * this replaces always carried the key — so NON_NULL sits on the one component
 * the map left off: a payment with no owner party has no related party.
 */
@JsonPropertyOrder({"paymentId", "amount", "refundRef", "refundedTotal", "status", "reason", "relatedParty", "@type"})
public record RefundReceipt(
        @JsonProperty("paymentId") String paymentId,
        @JsonProperty("amount") MoneyDto amount,
        @JsonProperty("refundRef") String refundRef,
        @JsonProperty("refundedTotal") BigDecimal refundedTotal,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("relatedParty") List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) {

    public static RefundReceipt of(String paymentId, MoneyDto amount, String refundRef,
            BigDecimal refundedTotal, String status, String reason, String ownerPartyId) {
        return new RefundReceipt(paymentId, amount, refundRef, refundedTotal, status, reason,
                ownerPartyId == null ? null : List.of(RelatedPartyRef.customer(ownerPartyId)),
                "Refund");
    }
}
