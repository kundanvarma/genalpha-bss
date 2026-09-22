package com.bss.loyalty.dto;

import com.bss.loyalty.entity.LoyaltyMember;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A member's card: the party is the id, the balance is the ledger's sum. */
@JsonPropertyOrder({"id", "balance", "tier", "enrolledAt", "@type"})
public record LoyaltyMemberView(
        String id,
        long balance,
        String tier,
        OffsetDateTime enrolledAt,
        @JsonProperty("@type") String type) {

    public static LoyaltyMemberView of(LoyaltyMember m) {
        return new LoyaltyMemberView(m.getId(), m.getBalance(), m.getTier(), m.getEnrolledAt(),
                "LoyaltyProgramMember");
    }
}
