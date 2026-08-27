package com.bss.ordering.exception;

/**
 * The ordering party's credit information is FROZEN at the bureau (the
 * voluntary freeze) — not a decline: the customer either lifts the freeze
 * or takes the prepaid path. Maps to HTTP 422 with the machine-readable
 * code CREDIT_FROZEN, which is what the storefront keys the prepaid offer
 * on.
 */
public class CreditFrozenException extends RuntimeException {

    public CreditFrozenException(String message) {
        super(message);
    }
}
