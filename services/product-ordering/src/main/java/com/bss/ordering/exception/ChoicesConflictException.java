package com.bss.ordering.exception;

/**
 * The household has too few Choices left for the benefits on the order (or
 * two picks raced for the last one and this one lost). Maps to HTTP 409: the
 * request was fine, current state refuses it.
 */
public class ChoicesConflictException extends RuntimeException {

    public ChoicesConflictException(String message) {
        super(message);
    }
}
