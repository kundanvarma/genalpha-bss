package com.bss.quote.dto;

import com.bss.quote.entity.OpportunityItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A line on a deal: the offering, how many, the negotiated unit price and the line total. */
@JsonPropertyOrder({"id", "offeringId", "offeringName", "quantity", "unitPrice", "lineTotal", "currency"})
public record OpportunityItemView(String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String offeringId,
        String offeringName, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency) {

    public static OpportunityItemView of(OpportunityItem i) {
        return new OpportunityItemView(i.getId(), i.getOfferingId(), i.getOfferingName(), i.getQuantity(),
                i.getUnitPrice(), i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())), i.getCurrency());
    }
}
