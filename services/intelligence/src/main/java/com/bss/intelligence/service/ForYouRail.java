package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The signed-in customer's own rail: the offers, why, and — when a meter argues for it — the next rung up. */
@JsonPropertyOrder({"items", "interests", "retentionFlag", "upsell", "caption", "generatedAt", "cached"})
public record ForYouRail(List<OfferRef> items, List<String> interests, boolean retentionFlag,
        @JsonInclude(JsonInclude.Include.NON_NULL) Upsell upsell,
        String caption, String generatedAt, boolean cached) {

    public ForYouRail cached(boolean value) {
        return new ForYouRail(items, interests, retentionFlag, upsell, caption, generatedAt, value);
    }

    /** The usage-aware upsell: the meter that is nearly drained and the cheapest fuller plan. The
     * meter's own values are another component's numbers and stay as they came. */
    @JsonPropertyOrder({"bucketName", "usedPct", "usedValue", "currentAllowance", "units",
            "suggestedOffering", "suggestedAllowance"})
    public record Upsell(String bucketName, long usedPct, Object usedValue, Object currentAllowance,
            Object units, OfferRef suggestedOffering, double suggestedAllowance) {
    }
}
