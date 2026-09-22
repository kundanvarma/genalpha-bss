package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/** What twin seeding minted, by offering name, and the privacy sentence the report carries on its face. */
@JsonPropertyOrder({"cloneId", "sourceId", "seeded", "distribution", "privacy"})
public record TwinBaseReceipt(String cloneId, String sourceId, int seeded, Map<String, Integer> distribution,
        String privacy) {
}
