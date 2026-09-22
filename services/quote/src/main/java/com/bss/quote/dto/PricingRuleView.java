package com.bss.quote.dto;

import com.bss.quote.entity.QuotePricingRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A CPQ pricing rule: a discount from a quantity up, optionally only for a CDP segment. */
@JsonPropertyOrder({"id", "offeringName", "minQuantity", "segment", "discountPercent"})
public record PricingRuleView(String id, String offeringName, int minQuantity,
        @JsonInclude(JsonInclude.Include.NON_NULL) String segment, BigDecimal discountPercent) {

    public static PricingRuleView of(QuotePricingRule r) {
        return new PricingRuleView(r.getId(), r.getOfferingName(), r.getMinQuantity(), r.getSegment(),
                r.getDiscountPercent());
    }
}
