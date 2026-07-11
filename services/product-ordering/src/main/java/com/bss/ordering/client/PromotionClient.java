package com.bss.ordering.client;

/**
 * View of the promotion service (TMF671): validate a code at order capture,
 * redeem it for the owner when the order completes.
 */
public interface PromotionClient {

    boolean isValid(String code);

    void redeem(String code, String ownerPartyId);
}
