package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The lead signal the sales funnel reads: a known consented prospect, and how it engaged. */
@JsonPropertyOrder({"email", "knownProspect", "engagement", "engaged"})
public record LeadSignal(String email, boolean knownProspect, String engagement, boolean engaged) {
}
