package com.bss.ordering.client;

/**
 * Read-side view of the party-account service (TMF666), as much of it as
 * order orchestration needs.
 */
public interface PartyClient {

    boolean billingAccountExists(String id);

    /** The organization a party belongs to, or empty (unknown party ⇒ empty). */
    java.util.Optional<String> organizationOf(String partyId);

    /** The ACTIVE person-payer of this party, if any (household billing). */
    java.util.Optional<String> householdPayerOf(String partyId);

    /** The household link in ANY state: {id, status, role}, or empty. */
    java.util.Optional<java.util.Map<String, Object>> householdLinkOf(String partyId);

    /** The dependents of a payer: [{id, givenName, familyName, status, role}]. */
    java.util.List<java.util.Map<String, Object>> dependentsOf(String payerId);
}
