package com.bss.party.privacy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /privacy/v1/erase body: the party to erase (DPO only). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(String partyId) {
}
