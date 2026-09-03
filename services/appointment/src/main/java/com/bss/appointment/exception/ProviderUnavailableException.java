package com.bss.appointment.exception;

/** The tenant's scheduling provider did not answer usefully — surfaced as 502, never faked. */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message) {
        super(message);
    }
}
