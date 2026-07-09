package com.bss.ordering.exception;

/**
 * A business-rule rejection of an order request (unknown references, illegal
 * state transitions). Maps to HTTP 400 with the TMF error body.
 */
public class OrderValidationException extends RuntimeException {

    public OrderValidationException(String message) {
        super(message);
    }
}
