package com.bss.ordering.exception;

/**
 * The order contains an offering that requires a verified real-world
 * identity (postpaid, regulated), and the buyer has not stepped up. Surfaced
 * as HTTP 403 with a machine-readable code so the channel knows to launch a
 * BankID/Vipps step-up and retry — not a generic authorization failure.
 */
public class VerifiedIdentityRequiredException extends RuntimeException {

    public VerifiedIdentityRequiredException(String offeringName) {
        super("'" + offeringName + "' requires a verified identity (BankID); step up and retry");
    }
}
