package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The one thing a campaign edit may change: its lifecycle status. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignPatch(String status) {
}
