package com.bss.agreement.exception;

/**
 * The request is well-formed but loses to current state — reserving more than
 * is available. Maps to HTTP 409 with the TMF error body.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
