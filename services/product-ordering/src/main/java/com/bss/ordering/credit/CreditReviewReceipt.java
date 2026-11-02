package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the customer gets back after asking for a person (#422): that it is
 * filed, the ticket that carries it, and what happens next. A second click
 * answers the same receipt with {@code alreadyRequested: true} — one review
 * per order, never two tickets.
 */
@JsonPropertyOrder({"orderId", "status", "alreadyRequested", "requestedAt", "ticketId", "nextSteps"})
public record CreditReviewReceipt(
        String orderId,
        String status,
        boolean alreadyRequested,
        String requestedAt,
        String ticketId,
        String nextSteps) {
}
