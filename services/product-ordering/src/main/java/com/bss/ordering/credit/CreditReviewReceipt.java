package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the customer gets back after asking for a person (#422): that it is
 * filed, the ticket that carries it, and what happens next. A second click
 * answers the same receipt with {@code alreadyRequested: true} — one review
 * per decision, never two tickets. {@code orderId} is absent for a decision
 * whose order was never placed; {@code ticketId} is absent for the few
 * seconds a first request is still with the care queue.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"orderId", "decisionId", "status", "alreadyRequested", "requestedAt", "ticketId", "nextSteps"})
public record CreditReviewReceipt(
        String orderId,
        String decisionId,
        String status,
        boolean alreadyRequested,
        String requestedAt,
        String ticketId,
        String nextSteps) {
}
