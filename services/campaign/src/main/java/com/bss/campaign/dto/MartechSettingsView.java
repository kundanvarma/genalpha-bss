package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The tenant's guardrails: the marketing-touch budget and the quiet hours, each with an "is it on" flag. */
@JsonPropertyOrder({"maxMarketingMessages", "perDays", "capActive", "quietStart", "quietEnd", "timeZone",
        "quietActive", "@type"})
public record MartechSettingsView(
        int maxMarketingMessages,
        int perDays,
        boolean capActive,
        @JsonInclude(JsonInclude.Include.NON_NULL) String quietStart,
        @JsonInclude(JsonInclude.Include.NON_NULL) String quietEnd,
        @JsonInclude(JsonInclude.Include.NON_NULL) String timeZone,
        boolean quietActive,
        @JsonProperty("@type") String type) {
}
