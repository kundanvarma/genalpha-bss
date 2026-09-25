package com.bss.som.client;

/**
 * The partner-fulfilment seam: a sold partner service (Netflix, Storytel…)
 * activates on the PARTNER's platform, not our network. One implementation
 * per partner integration; dev ships a mock that mints activation codes —
 * the same pluggable pattern as the PSP, the porting clearinghouse and the
 * SIM platform. Settlement/revenue share is a deliberate v2.
 */
public interface PartnerEntitlementClient {

    /** Who provides this seam for the fleet: the adapter in use, named honestly. */
    default String vendor() {
        return getClass().getSimpleName();
    }

    /** Provision the entitlement with the partner; returns the activation code. */
    String activate(String offeringName, String customerPartyId);
}
