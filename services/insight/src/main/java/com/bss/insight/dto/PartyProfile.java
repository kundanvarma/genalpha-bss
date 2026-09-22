package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A known customer's interests, merged across every browser they stitched. */
@JsonPropertyOrder({"partyId", "interests"})
public record PartyProfile(String partyId, List<String> interests) {
}
