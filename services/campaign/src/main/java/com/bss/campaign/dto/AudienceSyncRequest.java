package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Push an insight segment to the tenant's social platform as the named custom audience. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudienceSyncRequest(String segmentName, String audienceId) {
}
