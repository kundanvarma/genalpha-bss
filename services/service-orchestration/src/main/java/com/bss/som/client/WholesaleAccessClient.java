package com.bss.som.client;

/**
 * The wholesale-access seam: to sell retail broadband over open access, we place
 * an access-seeker order UPSTREAM to the fibre owner's OSS and it activates the
 * line. One implementation per owner integration; dev ships a mock. MEF LSO Sonata
 * is the wire shape a real adapter targets — the same pluggable pattern as the PSP,
 * the porting clearinghouse and the shipping carriers.
 */
public interface WholesaleAccessClient {

    /** Place an access-seeker order with the owner; returns its reference + state. */
    AccessOrderResult order(String accessOwner, String accessLayer, Integer bandwidthMbps,
            String postCode, String serviceId, String buyerRef);

    record AccessOrderResult(String externalId, String state) {
    }
}
