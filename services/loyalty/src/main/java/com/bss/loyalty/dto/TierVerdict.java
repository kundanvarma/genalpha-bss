package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The machine read billing puts into the pricing context; "none" for a non-member. */
@JsonPropertyOrder({"partyId", "tier"})
public record TierVerdict(String partyId, String tier) {
}
