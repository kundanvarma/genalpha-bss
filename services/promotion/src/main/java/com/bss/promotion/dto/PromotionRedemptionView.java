package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** One customer's claim on a promotion — what the billing run reads. */
@JsonPropertyOrder({"id", "promotionId", "name", "code", "relatedPartyId", "percentage",
        "appliesTo", "monthsLeft", "@type"})
public record PromotionRedemptionView(
        String id,
        String promotionId,
        String name,
        String code,
        String relatedPartyId,
        BigDecimal percentage,
        List<String> appliesTo,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer monthsLeft,
        @JsonProperty("@type") String type) {

    public static PromotionRedemptionView of(String id, String promotionId, String name,
            String code, String relatedPartyId, BigDecimal percentage, List<String> appliesTo,
            Integer monthsLeft) {
        return new PromotionRedemptionView(id, promotionId, name, code, relatedPartyId,
                percentage, appliesTo, monthsLeft, "PromotionRedemption");
    }
}
