package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A club's season tally from its members' codes: joiners, and the ones whose reward has been paid. */
@JsonPropertyOrder({"clubOrgId", "joined", "rewarded"})
public record ClubTally(String clubOrgId, long joined, long rewarded) {
}
