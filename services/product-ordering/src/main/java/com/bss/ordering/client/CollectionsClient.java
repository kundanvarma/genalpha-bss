package com.bss.ordering.client;

import java.util.Optional;

/**
 * Is a household in collections? (#391, Benefit Choices eligibility). Ordering
 * asks only when an order picks a new benefit, and hands the answer to policy as
 * a fact; the operator's rule decides. Empty when billing could not say — the
 * fact is then absent and a rule that needs it does not fire (written down as a
 * limit: an unreachable billing never refuses a pick).
 */
public interface CollectionsClient {

    Optional<Boolean> inCollections(String partyId);
}
