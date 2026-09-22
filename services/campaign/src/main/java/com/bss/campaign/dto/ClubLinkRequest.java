package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Tie my code to my local club (empty or absent = untie). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClubLinkRequest(String clubOrgId) {
}
