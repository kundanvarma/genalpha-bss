package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The public score of an area's goal — joined / target / percent — a number, never a person. */
@JsonPropertyOrder({"id", "name", "areaCode", "target", "joined", "percent", "unlocked"})
public record CommunityGoalView(String id, String name, String areaCode, int target, long joined, long percent,
        boolean unlocked) {
}
