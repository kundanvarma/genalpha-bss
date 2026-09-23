package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The bodies of the inventory's line actions. A record cannot carry a field
 * it does not declare; a missing body is the {@code EMPTY} of its kind.
 */
public final class LineRequests {

    private LineRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimPinRequest(String newPin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimReplaceRequest(String reason) {
    }

    /** A vacation hold: a reason, and either an end date or a number of days. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuspendRequest(String reason, String until, Long days) {
        public static final SuspendRequest EMPTY = new SuspendRequest(null, null, null);
    }

    /** A barring: a reason and the profile knobs (the emergency whitelist is not one). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RestrictRequest(String reason, Map<String, Object> profile) {
        public static final RestrictRequest EMPTY = new RestrictRequest(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferRequest(String toPartyId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MigrateRequest(String deliveryPoint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TerminateRequest(String reason) {
        public static final TerminateRequest EMPTY = new TerminateRequest(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PoolRequest(String name, String resourceType, String prefix, Long nextValue) {
    }
}
