package com.bss.ordering.exception;

/**
 * A dependent service failed or was unreachable during orchestration. Maps to
 * HTTP 502 with the TMF error body; the order itself is left unchanged (the
 * surrounding transaction rolls back).
 */
public class DownstreamException extends RuntimeException {

    public DownstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
