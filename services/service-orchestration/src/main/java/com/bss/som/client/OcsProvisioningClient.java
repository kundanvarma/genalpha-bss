package com.bss.som.client;

/**
 * The Online Charging System seam. The OCS is the charging master — it owns
 * rate plans, real-time counters, rollover policy; the network charges
 * against it over Gy/CHF and this BSS never sits in that path. What the BSS
 * owes the OCS is LIFECYCLE: when a line activates, its subscriber and
 * buckets must exist there under the rate plan the catalog references
 * (spec characteristic chargingSpecId), and a plan change must swap it.
 *
 * Fail-open by contract: charging provisioning must never block activation —
 * an unreachable OCS is logged and reconciled later, exactly like the SIM
 * platform and the partner seams.
 */
public interface OcsProvisioningClient {

    void provision(String tenantId, String partyId, String serviceId, String chargingSpecId);

    void changeRatePlan(String tenantId, String serviceId, String chargingSpecId);

    /** Pause / unpause charging for a line (vacation hold). Fail-open. */
    void suspend(String tenantId, String serviceId);

    void resume(String tenantId, String serviceId);

    /** The line changed hands: the OCS subscriber follows its new owner
     * (buckets and rollover stay with the SERVICE). Fail-open. */
    void transfer(String tenantId, String serviceId, String newPartyId);
}
