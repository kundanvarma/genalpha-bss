package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Choices switched off (tests, deployments without the ledger): every hold passes, nothing is kept. */
@Component
@ConditionalOnProperty(name = "bss.choices.enabled", havingValue = "false")
public class NoopChoicesClient implements ChoicesClient {

    @Override
    public HoldOutcome hold(String ownerPartyId, String orderId, String orderItemId, String offeringId,
            String offeringName, int cost, List<Source> replaces) {
        return HoldOutcome.held();
    }

    @Override
    public void settle(String orderId, String ownerPartyId, List<Grant> grants, List<Ending> endings) {
    }

    @Override
    public Swaps swaps(String ownerPartyId) {
        return new Swaps(0, null);
    }

    @Override
    public void release(String orderId) {
    }
}
