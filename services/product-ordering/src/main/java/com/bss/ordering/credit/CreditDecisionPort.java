package com.bss.ordering.credit;

/**
 * The credit-decision seam (Norway rails, P3): synchronous bureau check at
 * postpaid signup. Licensed bureaus answer score + payment-remarks over an
 * API; telecom postpaid signup is a recognized legitimate purpose. The port
 * is deliberately narrow — a DECISION, a coarse band, and whether remarks
 * exist. The report itself never crosses this boundary, and the party is
 * addressed by an OPAQUE reference (a real driver resolves it to a national
 * id inside its own walls).
 *
 * <p>{@code frozen} is first-class: the voluntary credit freeze means "you
 * cannot assess me" — the channel offers the prepaid path or asks the
 * customer to lift the freeze; it is not a decline.
 */
public interface CreditDecisionPort {

    String APPROVE = "approve";
    String REVIEW = "review";
    String DECLINE = "decline";
    String FROZEN = "frozen";

    /** decision: approve | review | decline | frozen. */
    record Decision(String decision, String scoreBand, boolean remarksPresent) {
    }

    /** Assess for the given purpose; may throw — callers fail OPEN (house default). */
    Decision assess(String nationalIdRef, String purpose);
}
