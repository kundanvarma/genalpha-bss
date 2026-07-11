package com.bss.payment.exception;

/**
 * The PSP demands strong customer authentication (3-D Secure / BankID)
 * before it will hold funds. Surfaced as HTTP 402 with the challenge URL;
 * the channel completes it and retries the authorization with the same
 * correlator (so the retry is idempotent).
 */
public class ScaRequiredException extends RuntimeException {

    private final String actionUrl;

    public ScaRequiredException(String actionUrl) {
        super("strong customer authentication required");
        this.actionUrl = actionUrl;
    }

    public String getActionUrl() {
        return actionUrl;
    }
}
