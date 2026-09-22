package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One next-best-action receipt: which journey spoke to the customer this moment, which was held, and why. */
@JsonPropertyOrder({"partyId", "winnerJourneyId", "heldJourneyId", "reason", "decidedAt", "decisionId"})
public record ArbitrationDecisionView(
        String partyId,
        String winnerJourneyId,
        String heldJourneyId,
        String reason,
        OffsetDateTime decidedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decisionId) {
}
