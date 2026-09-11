package com.bss.som.client;

/**
 * The device-entitlement seam: the orchestrator tells the entitlement server
 * which SIM belongs to which line and plan, so phones asking "what may I
 * use?" get the truth on their next check-in. Fail-open like the OCS seam:
 * an unreachable entitlement server never blocks activation.
 */
public interface EntitlementClient {

    /** A line activated: bind its SIM (ICCID) — and number — to the party and the plan it was sold as. */
    void bind(String tenantId, String partyId, String serviceId, String offeringId, String msisdn, String iccid);

    /** The line moved to another plan. */
    void changeOffering(String tenantId, String serviceId, String offeringId);

    /** The line's SIM was replaced (lost / stolen / upgrade / eSIM transfer): re-bind the new card. */
    void rebind(String tenantId, String serviceId, String iccid);
}
