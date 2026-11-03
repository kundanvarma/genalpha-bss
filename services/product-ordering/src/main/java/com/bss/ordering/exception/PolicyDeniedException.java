package com.bss.ordering.exception;

import com.bss.ordering.dto.CreditDecisionLink;

/**
 * An order refused by a data-authored business rule (policy component). The
 * order is well-formed but not permitted — maps to HTTP 422 with the rule's
 * customer-facing message, so the channel shows why without a redeploy having
 * introduced the rule. When the refusal left a credit decision the customer
 * may contest (#422 review), the 422 points at it.
 */
public class PolicyDeniedException extends RuntimeException {

    private final transient CreditDecisionLink creditDecision;

    public PolicyDeniedException(String message) {
        this(message, null);
    }

    public PolicyDeniedException(String message, CreditDecisionLink creditDecision) {
        super(message);
        this.creditDecision = creditDecision;
    }

    /** The credit decision this refusal left behind, when the customer should be shown it; else null. */
    public CreditDecisionLink creditDecision() {
        return creditDecision;
    }
}
