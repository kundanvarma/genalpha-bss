package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The storefront's three anonymous writes. A record cannot carry a field it does not declare. */
public final class VisitorRequests {

    private VisitorRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consent(String visitorId, Boolean analytics, Boolean personalization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String visitorId, String type, String category, String offeringId, String utmSource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stitch(String visitorId) {
    }
}
