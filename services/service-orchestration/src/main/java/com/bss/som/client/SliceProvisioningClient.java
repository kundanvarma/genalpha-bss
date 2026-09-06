package com.bss.som.client;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The network-slice seam. On a 5G standalone core, WHICH SLICE a line rides is
 * decided by the core's policy function / slice selection (3GPP PCF + NSSF, or
 * a vendor's slice manager — Mavenir, Ericsson, Nokia, Samsung). That is a
 * different system from the OCS (Mavenir CCS, Matrixx, Amdocs, CSG…), which
 * only RATES the traffic wherever it rides. So the BSS talks to two seams: the
 * OCS for charging lifecycle, this one for priority. What the BSS owes the
 * core is intent — "this line rides profile P until T" — never QoS mechanics.
 *
 * Fail-open by contract: slice provisioning must never block an order; an
 * unreachable core is logged, the order completes, and the profile is
 * reconciled (re-applied) by the expiry sweep. The customer sees the truth
 * on the service record either way.
 */
public interface SliceProvisioningClient {

    /** What the core currently applies to a line — profile name and expiry, if any. */
    record SliceState(String profile, OffsetDateTime until, boolean active) { }

    /** Apply a slice profile to a line until {@code until} (null = open-ended). */
    Optional<SliceState> apply(String tenantId, String serviceId, String profile, OffsetDateTime until);

    /** Back to the default (best-effort) slice. */
    void release(String tenantId, String serviceId);

    Optional<SliceState> current(String tenantId, String serviceId);

    /** What the slice DELIVERED to the line over the last window (the core's own KPI). */
    record SliceQuality(Double measuredDlMbps, Double measuredLatencyMs, Integer windowMinutes) { }

    default Optional<SliceQuality> quality(String tenantId, String serviceId) {
        return Optional.empty();
    }
}
