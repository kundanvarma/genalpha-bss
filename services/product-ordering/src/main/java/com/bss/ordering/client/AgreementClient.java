package com.bss.ordering.client;

import java.util.List;
import java.util.Map;

/**
 * Write-side view of the agreement service (TMF651): order completion mints
 * an active agreement for every offering that carries a commitment term.
 */
public interface AgreementClient {

    void activate(String name, String ownerPartyId, List<Map<String, Object>> items, int commitmentMonths);

    /**
     * The party's ACTIVE agreements, for the commitment guard on plan changes.
     * Fails open (empty) when the component is absent or unreachable — an
     * unreachable agreement service must not strand customers on their plan.
     */
    List<Map<String, Object>> activeAgreements(String ownerPartyId);
}
