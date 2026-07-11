package com.bss.payment.psp;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The payment service provider boundary. Vendor-neutral by design: the dev
 * deployment uses the mock adapter; a Stripe/Adyen adapter implements this
 * same interface without touching the service. The lifecycle mirrors a real
 * PSP — authorize (hold funds, possibly with a strong-customer-auth
 * challenge), capture (move the money), refund (give it back).
 */
public interface PspAdapter {

    /**
     * Attempts to authorize the amount against the given payment method.
     * Card details live only in this call — never stored. The idempotency
     * key lets the PSP collapse a retried authorization onto the first,
     * so a double-submit never double-holds.
     */
    Authorization authorize(BigDecimal amount, String currency, Map<String, Object> paymentMethod,
            String idempotencyKey);

    /** Settle a prior authorization — this is where money actually moves. */
    Capture capture(String authorizationCode, BigDecimal amount, String currency);

    /** Return captured funds to the payer (full or partial). */
    Refund refund(String authorizationCode, BigDecimal amount, String currency);

    /** Which PSP answered — recorded on the payment for reconciliation. */
    String provider();

    /**
     * @param requiresAction the PSP needs a strong-customer-authentication
     *   step (3-D Secure, BankID) before it will hold funds; the channel
     *   completes {@code actionUrl} and retries. approved is false until then.
     */
    record Authorization(boolean approved, boolean requiresAction, String actionUrl,
            String authorizationCode, String methodLabel, String declineReason) {

        public static Authorization approved(String code, String label) {
            return new Authorization(true, false, null, code, label, null);
        }

        public static Authorization declined(String label, String reason) {
            return new Authorization(false, false, null, null, label, reason);
        }

        public static Authorization challenge(String actionUrl, String label) {
            return new Authorization(false, true, actionUrl, null, label, null);
        }
    }

    record Capture(boolean settled, String captureRef, String failureReason) {
    }

    record Refund(boolean refunded, String refundRef, String failureReason) {
    }
}
