package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A customer's answer to "why?" about the credit check on THEIR order (#422):
 * the decision, the reason in plain words, what data was used (categories,
 * never the bureau's report — there is none here to show), that a person
 * reviews every decline, and how to ask for that review. Built for GDPR
 * Art. 22(3) and Art. 15(1)(h) as read by the CJEU in C-203/22: the procedure
 * and principles actually applied, concise and intelligible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"orderId", "assessed", "decision", "outcome", "reasonCode", "reason", "scoreBand",
        "remarksPresent", "decidedAt", "bureau", "purpose", "dataUsed", "notKept", "humanReview", "contest"})
public record CreditExplanation(
        String orderId,
        boolean assessed,
        String decision,
        String outcome,
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
