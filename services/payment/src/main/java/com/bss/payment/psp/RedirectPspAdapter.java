package com.bss.payment.psp;

import com.bss.payment.entity.PspConfig;

import java.math.BigDecimal;

/**
 * The redirect/BNPL seam — a sibling to {@link PspAdapter}. Card PSPs authorize
 * synchronously; Klarna/PayPal create a session, the customer approves at the
 * provider (redirect), and confirmation is pull/webhook. Named adapters only —
 * money has no generic connector. Per-tenant config is passed in.
 */
public interface RedirectPspAdapter {

    /** The name used in payment_provider_config and the registry ('klarna', …). */
    String name();

    /** Create a session; returns where to send the customer to approve. */
    Session createSession(PspConfig cfg, BigDecimal amount, String currency, String returnUrl);

    /** Confirm a session after the customer approved (idempotent at the caller). */
    Confirmation confirm(PspConfig cfg, String sessionId);

    /** Capture the order (BNPL captures on ship, not at checkout). */
    Settlement capture(PspConfig cfg, String sessionId, BigDecimal amount, String currency);

    /** Refund a captured order (full or partial). */
    Settlement refund(PspConfig cfg, String sessionId, BigDecimal amount, String currency);

    record Session(String sessionId, String redirectUrl) {
    }

    record Confirmation(boolean approved, BigDecimal amount, String currency,
            String authorizationCode, String methodLabel, String declineReason) {
    }

    /** Result of a capture/refund — settled + the provider's reference, or why not. */
    record Settlement(boolean ok, String reference, String failureReason) {
    }
}
