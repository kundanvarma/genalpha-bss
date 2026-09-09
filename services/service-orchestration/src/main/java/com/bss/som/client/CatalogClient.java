package com.bss.som.client;

import java.util.Optional;

/**
 * The one catalog fact the SOM needs: what KIND of thing is this offering?
 * The category decides fulfilment — a network line draws a number and a SIM,
 * a partner service mints an entitlement, a security product toggles a
 * feature, insurance is billing-only. Fails open to empty: an unreachable
 * catalog means "treat it as a line", the historical behavior.
 */
public interface CatalogClient {

    Optional<String> categoryOf(String offeringId);

    /** The offering's display name — the service and the product must agree
     * on it, or nothing downstream can correlate them. */
    Optional<String> nameOf(String offeringId);

    /**
     * The offering's charging reference (spec characteristic chargingSpecId):
     * the rate-plan/counter template that lives in the OPERATOR'S OCS. Empty
     * means the product has no online-charging footprint — nothing to
     * provision there.
     */
    Optional<String> chargingSpecOf(String offeringId);

    /**
     * The offering's slice intent (spec characteristics `sliceProfile` and,
     * for a time-boxed boost pass, `boostHours`). Empty = best effort.
     */
    Optional<SliceIntent> sliceIntentOf(String offeringId);

    /** The apps a plan zero-rates (spec characteristic "zeroRatedApps", comma-separated) — empty when none. */
    java.util.List<String> zeroRatedAppsOf(String offeringId);

    /**
     * @param profile          the core's slice profile name
     * @param boostHours       present = a time-boxed pass
     * @param chargingSpecId   the OCS rate plan the line moves to while on the slice (slice-aware charging); null = charging unchanged
     * @param guaranteedDlMbps a sold guarantee the assurance side measures against; null = best-effort priority
     */
    record SliceIntent(String profile, Integer boostHours, String chargingSpecId, Integer guaranteedDlMbps) {
        public SliceIntent(String profile, Integer boostHours) {
            this(profile, boostHours, null, null);
        }
    }
}
