package com.bss.entitlement.client;

import java.util.Optional;

/**
 * The SM-DP+ seam (GSMA SGP.22 ES2+, the operator ↔ SM-DP+ interface): the
 * BSS ORDERS an eSIM profile, CONFIRMS it (which yields the matching id the
 * phone's activation code carries), CANCELS or RELEASES it — and the SM-DP+
 * reports download progress back on its own notification function. Nobody
 * here hosts profiles: a certified SM-DP+ (Thales, IDEMIA, G+D, Kigen…) does,
 * behind a per-tenant binding; {@code mock-smdp} speaks the same shape in dev.
 */
public interface SmdpClient {

    /** Whether this tenant has an SM-DP+ bound. */
    boolean enabled(String tenantId);

    /** downloadOrder + confirmOrder(releaseFlag=true): a profile ready for download by this EID. */
    Optional<Profile> order(String tenantId, String eid, String iccid, String profileType);

    /** cancelOrder: the profile will not be downloaded after all. */
    boolean cancel(String tenantId, String iccid, String matchingId);

    /** releaseProfile: the profile is no longer bound to a device. */
    boolean release(String tenantId, String iccid);

    /** An ordered profile: what the activation code is made of. */
    record Profile(String iccid, String matchingId, String smdpAddress) {
        /** SGP.22 §4.1: {@code LPA:1$<SM-DP+ address>$<matching id>}. */
        public String activationCode() {
            return "LPA:1$" + smdpAddress + "$" + matchingId;
        }
    }
}
