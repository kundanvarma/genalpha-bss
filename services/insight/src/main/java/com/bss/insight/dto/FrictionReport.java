package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** A period of desk telemetry turned into where staff stumble. */
@JsonPropertyOrder({"enabled", "days", "events", "activeStaff", "abandonedForms", "repeatedForms", "emptySearches",
        "copilotRewrites", "unusedFeatures", "journeysWithoutHoldout"})
public record FrictionReport(boolean enabled, int days, int events, int activeStaff, List<AbandonedForm> abandonedForms,
        List<RepeatedForm> repeatedForms, List<EmptySearch> emptySearches, List<CopilotRewrite> copilotRewrites,
        List<String> unusedFeatures, List<JourneyWithoutHoldout> journeysWithoutHoldout) {

    /** A form started and left; stopField is where most people stopped. */
    @JsonPropertyOrder({"form", "count", "stopField"})
    public record AbandonedForm(String form, int count, @JsonInclude(JsonInclude.Include.NON_NULL) String stopField) {
    }

    /** The same form submitted 3+ times by one person with the same values. */
    @JsonPropertyOrder({"desk", "form", "count", "commonValues"})
    public record RepeatedForm(String desk, String form, int count, Map<String, String> commonValues) {
    }

    @JsonPropertyOrder({"query", "count"})
    public record EmptySearch(String query, int count) {
    }

    @JsonPropertyOrder({"form", "count"})
    public record CopilotRewrite(String form, int count) {
    }

    @JsonPropertyOrder({"journeyId", "name"})
    public record JourneyWithoutHoldout(String journeyId, String name) {
    }
}
