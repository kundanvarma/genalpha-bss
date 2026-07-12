package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Deployments without the agreement component skip commitment minting. */
@Component
@ConditionalOnProperty(name = "bss.agreement.enabled", havingValue = "false")
public class NoopAgreementClient implements AgreementClient {

    @Override
    public void activate(String name, String ownerPartyId, List<Map<String, Object>> items,
            int commitmentMonths) {
        // intentionally empty
    }

    @Override
    public List<Map<String, Object>> activeAgreements(String ownerPartyId) {
        return List.of();
    }
}
