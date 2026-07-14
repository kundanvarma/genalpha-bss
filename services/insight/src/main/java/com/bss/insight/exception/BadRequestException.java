package com.bss.insight.exception;

/**
 * Client error outside bean validation (unknown filter attributes, malformed
 * filter values). Maps to HTTP 400 with the TMF error body.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
