package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Who to erase. The map read this with {@code String.valueOf} on the body's partyId,
 * so a body with no party at all looked up the literal {@code "null"} and
 * deleted nothing by accident. A record's honest null would match
 * {@code owner_party_id IS NULL} — every guest booking — so a nameless erasure
 * is the 400 this door always meant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(String partyId) {

    public boolean names() {
        return partyId != null && !partyId.isBlank();
    }
}
