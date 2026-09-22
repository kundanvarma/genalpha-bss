package com.bss.intelligence.fairplay;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The right-plan sweep: who is on an oversized plan, and the cheaper plan
 * that still fits, with the assumptions on its face. */
@JsonPropertyOrder({"@type", "suggestions", "rightSized", "assumptions"})
public record FairPlaySweep(
        @JsonProperty("@type") String type,
        List<RightPlanSuggestion> suggestions,
        int rightSized,
        List<String> assumptions) {

    /** One customer, one oversized plan, one honest alternative. Also the
     * payload of RightPlanSuggestedEvent. */
    @JsonPropertyOrder({"partyId", "currentOffering", "currentMonthly", "usedGb", "allowanceGb",
            "suggested", "monthlySaving"})
    public record RightPlanSuggestion(String partyId, String currentOffering, BigDecimal currentMonthly,
            BigDecimal usedGb, BigDecimal allowanceGb, SuggestedPlan suggested, BigDecimal monthlySaving) {
    }

    @JsonPropertyOrder({"offeringName", "monthlyPrice", "allowanceGb"})
    public record SuggestedPlan(String offeringName, BigDecimal monthlyPrice, BigDecimal allowanceGb) {
    }
}
