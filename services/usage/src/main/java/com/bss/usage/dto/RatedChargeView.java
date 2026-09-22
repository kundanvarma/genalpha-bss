package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One rated overage line: what billing picks up, and the UsageRatedEvent's resource. */
@JsonPropertyOrder({"ownerPartyId", "name", "amount"})
public record RatedChargeView(String ownerPartyId, String name, Money amount) {
}
