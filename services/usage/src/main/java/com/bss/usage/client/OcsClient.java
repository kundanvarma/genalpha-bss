package com.bss.usage.client;

import java.util.List;
import java.util.Map;

/**
 * The usage component's read/credit window onto the Online Charging System.
 * The OCS owns the real-time truth (counters, rollover); this seam PROJECTS
 * it for the TMF654 facade and forwards top-up credits. Routed per tenant
 * ({@link TenantOcsRouter}): each operator's tenants.yml names the adapter
 * ({@code http} = the generic subscriber/bucket shape, {@code sigscale} =
 * SigScale OCS over TM Forum APIs). No OCS for a tenant = balances answer
 * empty and the facade says so honestly.
 *
 * Subscriber projection shape (what every adapter returns):
 * {@code {id, tenantId, partyId, serviceId, ratePlanId, status,
 *   buckets:[{id, name, ratePlanId, totalGB, usedGB, rolloverGB, rollover}]}}
 */
public interface OcsClient {

    /** Whether this tenant has any OCS behind the seam. */
    boolean enabled(String tenantId);

    List<Map<String, Object>> subscribersOf(String tenantId, String partyId);

    /** Credit a top-up onto a subscriber's data counter. */
    boolean credit(String tenantId, String subscriberId, double gb);
}
