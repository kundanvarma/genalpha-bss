package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One registered DecisionPoint: which policy answers it today and how much autonomy it has. */
@JsonPropertyOrder({"name", "subjectType", "autonomy", "description", "policy", "policyVersion", "source", "@type"})
public record DecisionPointView(
        String name,
        String subjectType,
        String autonomy,
        String description,
        String policy,
        String policyVersion,
        String source,
        @JsonProperty("@type") String type) {

    /** The same point re-labelled — a learning contract carries the point's keys under its own {@code @type}. */
    public DecisionPointView labelled(String type) {
        return new DecisionPointView(name, subjectType, autonomy, description, policy, policyVersion, source, type);
    }
}
