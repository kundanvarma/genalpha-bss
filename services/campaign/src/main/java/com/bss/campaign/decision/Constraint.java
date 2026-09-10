package com.bss.campaign.decision;

import java.util.Optional;

/**
 * A hard eligibility rule evaluated BEFORE any policy looks at the actions:
 * law, consent, policy, catalog availability. It removes an action and says
 * why; it never scores one. Every constraint that fired is written into the
 * decision record, so a receipt shows what the customer could not have been
 * offered and by which rule.
 */
public interface Constraint {

    String name();

    /** Empty when the action stays eligible; the reason when it is removed. */
    Optional<String> reject(String action, DecisionRequest request);
}
