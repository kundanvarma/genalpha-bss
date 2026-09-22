package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An area's unlock goal: a name, the area code it grows, and how many joiners unlock it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommunityGoalRequest(String name, String areaCode, Integer target) {
}
