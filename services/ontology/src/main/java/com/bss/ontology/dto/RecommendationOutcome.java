package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Noted: what became of a recommendation, so the ranking on this desk learns from it. */
@JsonPropertyOrder({"decisionId", "outcome", "said", "@type"})
public record RecommendationOutcome(String decisionId, String outcome, String said, @JsonProperty("@type") String type) {

    /** The body of the outcome call: the verdict word and, optionally, why. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String outcome, String reason) {
        public static final Request EMPTY = new Request(null, null);

        public String outcomeOrEmpty() {
            return outcome == null ? "" : outcome;
        }
    }
}
