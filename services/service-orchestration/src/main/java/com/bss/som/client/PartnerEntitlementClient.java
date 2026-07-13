package com.bss.som.client;

/**
 * The partner-fulfilment seam: a sold partner service (Netflix, Storytel…)
 * activates on the PARTNER's platform, not our network. One implementation
 * per partner integration; dev ships a mock that mints activation codes —
 * the same pluggable pattern as the PSP, the porting clearinghouse and the
 * SIM platform. Settlement/revenue share is a deliberate v2.
 */
public interface PartnerEntitlementClient {

    /** Provision the entitlement with the partner; returns the activation code. */
    String activate(String offeringName, String customerPartyId);
}
