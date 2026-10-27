package com.bss.som.client;

/**
 * The partner-fulfilment seam: a sold partner service (Netflix, Storytel…)
 * activates on the PARTNER's platform, not our network. One implementation
 * per partner integration; dev ships a mock that mints activation codes —
 * the same pluggable pattern as the PSP, the porting clearinghouse and the
 * SIM platform. Settlement/revenue share is a deliberate v2.
 *
 * <p>Both directions: a sold partner service starts with the partner, and an
 * ended one stops there too — without {@link #deactivate} a released benefit
 * would keep running on the partner's side, and keep costing.
 */
public interface PartnerEntitlementClient {

    /** Who provides this seam for the fleet: the adapter in use, named honestly. */
    default String vendor() {
        return getClass().getSimpleName();
    }

    /** Provision the entitlement with the partner; returns the activation code. */
    String activate(String offeringName, String customerPartyId);

    /** End the entitlement the activation code stands for; the customer's partner access stops. */
    void deactivate(String activationCode, String customerPartyId);
}
