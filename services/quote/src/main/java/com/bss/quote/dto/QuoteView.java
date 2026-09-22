package com.bss.quote.dto;

import com.bss.quote.entity.Quote;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The TMF648 quote as the API, the events and the consoles see it: the
 * lines, the monthly and one-time totals, the discount and its approval
 * gate, the narrative, and the order and agreement it became once accepted.
 */
@JsonPropertyOrder({"id", "href", "description", "state", "intent", "relatedParty", "quoteItem", "quoteTotalPrice",
        "quoteOneTimePrice", "discountPercent", "netMonthlyTotal", "approvalStatus", "narrative", "productOrder",
        "agreement", "signatureStatus", "signedBy", "signedAt", "@type"})
public record QuoteView(String id, String href, String description, String state,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef intent,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        List<QuoteItem> quoteItem, Money quoteTotalPrice,
        @JsonInclude(JsonInclude.Include.NON_NULL) Money quoteOneTimePrice,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal discountPercent,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal netMonthlyTotal,
        String approvalStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) String narrative,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef productOrder,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef agreement,
        String signatureStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) String signedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime signedAt,
        @JsonProperty("@type") String type) {

    public static final String AGREEMENT_PATH = "/tmf-api/agreementManagement/v4/agreement/";

    /** The view of a stored quote and its parsed lines. */
    public static QuoteView of(Quote quote, List<QuoteItem> items) {
        boolean oneTime = quote.getOneTimeTotal() != null && quote.getOneTimeTotal().signum() != 0;
        boolean discounted = quote.getDiscountPercent() != null && quote.getDiscountPercent().signum() != 0;
        BigDecimal net = null;
        if (discounted) {
            BigDecimal factor = BigDecimal.ONE.subtract(quote.getDiscountPercent().movePointLeft(2));
            net = quote.getMonthlyTotal().multiply(factor);
        }
        return new QuoteView(quote.getId(), quote.getHref(), quote.getDescription(), quote.getState(),
                quote.getIntentId() == null ? null : EntityRef.of(quote.getIntentId()),
                quote.getOwnerPartyId() == null ? null : List.of(RelatedPartyRef.customer(quote.getOwnerPartyId())),
                items,
                Money.monthly(quote.getMonthlyTotal(), quote.getCurrency()),
                oneTime ? Money.oneTime(quote.getOneTimeTotal(), quote.getCurrency()) : null,
                discounted ? quote.getDiscountPercent() : null,
                net,
                quote.getApprovalStatus(),
                quote.getNarrative(),
                quote.getProductOrderId() == null ? null : EntityRef.of(quote.getProductOrderId()),
                quote.getAgreementId() == null ? null
                        : EntityRef.at(quote.getAgreementId(), AGREEMENT_PATH + quote.getAgreementId()),
                quote.getSignatureStatus(), quote.getSignedBy(), quote.getSignedAt(), "Quote");
    }
}
