package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Set;

/** Vendor feed material: counts only. No hashes, no values, no tenant name. */
@JsonPropertyOrder({"days", "events", "activeStaff", "abandonedForms", "repeatedForms", "emptySearchCount", "copilotRewrites",
        "unusedFeatures"})
public record DeskExport(int days, int events, int activeStaff, List<Abandoned> abandonedForms, List<Repeated> repeatedForms,
        int emptySearchCount, List<FrictionReport.CopilotRewrite> copilotRewrites, List<String> unusedFeatures) {

    @JsonPropertyOrder({"form", "count", "stopField"})
    public record Abandoned(String form, int count, String stopField) {
    }

    @JsonPropertyOrder({"form", "count", "sharedFields"})
    public record Repeated(String form, int count, Set<String> sharedFields) {
    }
}
