package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One row of the consent ledger: what the platform holds and under which consent. */
@JsonPropertyOrder({"id", "visitorId", "partyId", "analyticsConsent", "personalizationConsent", "utmSource", "lastUpdate"})
public record ProfileRow(String id, String visitorId, String partyId, boolean analyticsConsent,
        boolean personalizationConsent, String utmSource, OffsetDateTime lastUpdate) {
}
