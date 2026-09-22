package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A shadow-operator clone is the onboarding receipt (unwrapped FIRST) plus
 * where it came from, the sandbox flag, and what was copied across.
 */
@JsonPropertyOrder({"made", "sourceId", "sandbox", "copied"})
public record CloneReceipt(@JsonUnwrapped OnboardReceipt made, String sourceId, boolean sandbox, CopyCounts copied) {

    /** How much of the source's shelf, rules and rate cards landed in the clone. */
    @JsonPropertyOrder({"categories", "specifications", "prices", "offerings", "policyRules", "rateCards"})
    public record CopyCounts(int categories, int specifications, int prices, int offerings, int policyRules,
            int rateCards) {

        public CopyCounts withRules(int policyRules, int rateCards) {
            return new CopyCounts(categories, specifications, prices, offerings, policyRules, rateCards);
        }
    }
}
