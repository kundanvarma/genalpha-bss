package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deployments without the promotion component accept no promo codes. */
@Component
@ConditionalOnProperty(name = "bss.promotion.enabled", havingValue = "false")
public class NoopPromotionClient implements PromotionClient {

    @Override
    public boolean isValid(String code) {
        return false;
    }

    @Override
    public void redeem(String code, String ownerPartyId) {
        // intentionally empty
    }
}
