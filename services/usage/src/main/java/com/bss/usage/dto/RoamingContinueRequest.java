package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /roamingLimit/continue: the audited "keep me roaming" election; staff name the party. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoamingContinueRequest(String partyId) {
}
