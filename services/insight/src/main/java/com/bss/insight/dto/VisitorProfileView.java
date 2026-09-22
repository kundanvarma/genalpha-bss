package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The raw profile — the back-office window and the suites' honesty probe. */
@JsonPropertyOrder({"visitorId", "partyId", "analyticsConsent", "personalizationConsent", "utmSource", "eventCount", "interests"})
public record VisitorProfileView(String visitorId, String partyId, boolean analyticsConsent,
        boolean personalizationConsent, String utmSource, long eventCount, List<Interest> interests) {

    @JsonPropertyOrder({"category", "views"})
    public record Interest(String category, long views) {
    }
}
