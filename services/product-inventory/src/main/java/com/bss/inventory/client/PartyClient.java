package com.bss.inventory.client;

import java.util.Map;
import java.util.Optional;

/** The one question inventory ever asks the party source: whose household
 * is this person in, with what status and role. */
public interface PartyClient {

    Optional<Map<String, Object>> householdLinkOf(String partyId);
}
