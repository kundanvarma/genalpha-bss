package com.bss.ordering.client;

/**
 * Read-side view of the party-account service (TMF666), as much of it as
 * order orchestration needs.
 */
public interface PartyClient {

    boolean billingAccountExists(String id);

    /** The organization a party belongs to, or empty (unknown party ⇒ empty). */
    java.util.Optional<String> organizationOf(String partyId);
}
