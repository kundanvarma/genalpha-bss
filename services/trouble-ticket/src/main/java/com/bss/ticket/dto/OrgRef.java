package com.bss.ticket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * The organisation that owns the queue a ticket sits in — the partner
 * boundary. Key order is the one the wire already has ({@code @referredType}
 * first); it came from a two-entry {@code Map.of}, which is salted per JVM,
 * so it is pinned here to what the old image printed, not to the order the
 * source declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"@referredType", "id"})
public record OrgRef(
        @JsonProperty("@referredType") String referredType,
        String id) {

    /** A null org is the 500 {@code Map.of} always answered; typing does not soften it. */
    public static OrgRef of(String orgId) {
        return new OrgRef("Organization", Objects.requireNonNull(orgId));
    }
}
