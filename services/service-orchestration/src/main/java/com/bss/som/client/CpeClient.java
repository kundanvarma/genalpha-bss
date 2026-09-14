package com.bss.som.client;

import java.util.Optional;

/**
 * The equipment seam: what the operator's ACS (TR-069 / USP) knows about the
 * router or ONT on a line, and the one action care asks for most. Fail-open —
 * an unreachable ACS is a "could not check", never a blocked line. Real:
 * Axiros, Friendly Technologies, Calix, Nokia Altiplano behind the same seam.
 */
public interface CpeClient {

    record CpeState(String state, long uptimeSeconds, String firmware, boolean firmwareOutdated,
            int wifiClients, String model, String serial, String lastSeen) {
    }

    /** empty = no equipment known or the ACS did not answer. */
    Optional<CpeState> state(String tenantId, String serviceId);

    /** true = the reboot was accepted by the ACS. */
    boolean reboot(String tenantId, String serviceId);

    boolean enabled();
}
