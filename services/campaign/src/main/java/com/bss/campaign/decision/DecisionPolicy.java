package com.bss.campaign.decision;

/**
 * The algorithm behind a DecisionPoint. Rules, hashed splits, threshold tuners,
 * bandits and, one day, sequential learners all implement this one contract, so
 * a process changes how it decides without changing where it decides.
 * A policy must be pure: same request, same answer — the seam does the logging.
 */
public interface DecisionPolicy {

    /** Stable name, e.g. {@code holdout-then-weighted-hash}. */
    String name();

    /** Bumped whenever the rule changes, so old decisions stay attributable. */
    String version();

    Decision decide(DecisionRequest request);
}
