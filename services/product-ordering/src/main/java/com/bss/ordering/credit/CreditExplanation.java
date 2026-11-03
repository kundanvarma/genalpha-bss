package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A customer's answer to "why?" about a credit check of THEIRS (#422): the
 * decision, the reason in plain words, what actually happened to the order
 * (on hold, released, cancelled, refused by the operator's rule, went ahead),
 * what data was used (categories, never the bureau's report — there is none
 * here to show), the route to a person, and how to ask for one. Built for GDPR
 * Art. 22(3) and Art. 15(1)(h) as read by the CJEU in C-203/22: the procedure
 * and principles actually applied, concise and intelligible.
 *
 * <p>{@code orderId} is absent when the order was never placed (the operator's
 * rule refused it and it rolled back); {@code decisionId} always names the
 * decision, which is the door then. {@code show} is ordering's answer to "should
 * a channel show this to the customer unprompted" — no channel works it out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"orderId", "decisionId", "assessed", "show", "decision", "outcomeCode", "outcome", "refusal",
        "reasonCode", "reason", "scoreBand", "remarksPresent", "decidedAt", "bureau", "purpose", "dataUsed",
        "notKept", "humanReview", "contest"})
public record CreditExplanation(
        String orderId,
        String decisionId,
        boolean assessed,
        boolean show,
        String decision,
        String outcomeCode,
        String outcome,
        String refusal,
        String reasonCode,
        String reason,
        String scoreBand,
        Boolean remarksPresent,
        String decidedAt,
        String bureau,
        String purpose,
        List<DataCategory> dataUsed,
        String notKept,
        String humanReview,
        Contest contest) {

    /** One kind of data the check used, as a stable code and a sentence. */
    @JsonPropertyOrder({"code", "label"})
    public record DataCategory(String code, String label) {
    }

    /** Can this decision be put to a person, how, and whether it already was. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"available", "how", "requested", "requestedAt", "ticketId", "nextSteps"})
    public record Contest(boolean available, String how, boolean requested, String requestedAt,
            String ticketId, String nextSteps) {
    }
}
