package com.bss.ordering.exception;

/**
 * An order refused by a data-authored business rule (policy component). The
 * order is well-formed but not permitted — maps to HTTP 422 with the rule's
 * customer-facing message, so the channel shows why without a redeploy having
 * introduced the rule.
 */
public class PolicyDeniedException extends RuntimeException {

    public PolicyDeniedException(String message) {
        super(message);
    }
}
