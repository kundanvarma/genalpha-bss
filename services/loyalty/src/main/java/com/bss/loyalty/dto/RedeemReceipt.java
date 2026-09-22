package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The member's card after the burn, plus what the points bought. */
@JsonPropertyOrder({"member", "redeemed"})
public record RedeemReceipt(@JsonUnwrapped LoyaltyMemberView member, Reward redeemed) {
}
