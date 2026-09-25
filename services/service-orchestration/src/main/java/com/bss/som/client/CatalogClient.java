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
     * The customer-facing service (TMF633) the offering's product spec names in
     * its {@code serviceSpecification[0]} — the catalog's OWN decomposition,
     * authored by a product manager, not inferred from a category string. The
     * CFS carries its fulfilment family as the characteristic
     * {@code fulfilmentFamily} (mobile | internet | tv | device | partner |
     * security). Empty when the spec names no CFS or the CFS is unreadable:
     * the SOM then falls back to {@code componentType(category)}, as it always
     * did, so an unfilled catalog changes nothing.
     */
    Optional<Cfs> cfsOf(String offeringId);

    /**
     * @param id     the TMF633 ServiceSpecification id
     * @param name   its display name ("Mobile line", "Broadband access", …)
     * @param family the fulfilment family it declares; null when the CFS declares none
     */
    record Cfs(String id, String name, String family) {
        public static final java.util.Set<String> FAMILIES =
                java.util.Set.of("mobile", "internet", "tv", "device", "partner", "security");
    }

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
