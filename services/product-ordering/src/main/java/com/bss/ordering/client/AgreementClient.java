package com.bss.ordering.client;

import java.util.List;
import java.util.Map;

/**
 * Write-side view of the agreement service (TMF651): order completion mints
 * an active agreement for every offering that carries a commitment term.
 */
public interface AgreementClient {

    void activate(String name, String ownerPartyId, List<Map<String, Object>> items, int commitmentMonths);
}
