package com.bss.usage.client;

import java.util.Map;
import java.util.Optional;

/** What gifting needs from the party source: who is in whose household,
 * and what to call them. */
public interface PartyClient {

    /** The raw individual: {id, givenName, familyName, householdPayer:{id,status,role}}. */
    Optional<Map<String, Object>> individualOf(String partyId);
}
