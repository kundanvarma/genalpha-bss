package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** What quote sends downstream: the order and agreement an accepted quote becomes, the narrative's context. */
public final class HandoffBodies {

    private HandoffBodies() {
    }

    /** TMF622 productOrder: one add per quote line, for the quote's party. */
    @JsonPropertyOrder({"productOrderItem", "relatedParty"})
    public record ProductOrderRequest(List<OrderItem> productOrderItem, List<RelatedPartyRef> relatedParty) {
    }

    @JsonPropertyOrder({"action", "productOffering"})
    public record OrderItem(String action, EntityRef productOffering) {

        public static OrderItem add(EntityRef productOffering) {
            return new OrderItem("add", productOffering);
        }
    }

    /** TMF651 agreement: the same party and lines, tagged with the quote it came from. */
    @JsonPropertyOrder({"name", "agreementType", "status", "engagedParty", "agreementItem", "characteristic"})
    public record AgreementRequest(String name, String agreementType, String status,
            List<RelatedPartyRef> engagedParty, List<AgreementItem> agreementItem,
            List<NameValue> characteristic) {
    }

    @JsonPropertyOrder({"productOffering"})
    public record AgreementItem(EntityRef productOffering) {
    }

    @JsonPropertyOrder({"name", "value"})
    public record NameValue(String name, String value) {
    }

    /** What the AI narrates from: the deal, its lines and its monthly total. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"description", "items", "monthlyTotal", "currency"})
    public record NarrativeContext(String description, List<QuoteItem> items, BigDecimal monthlyTotal,
            String currency) {
    }
}
