package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Whose messages to erase. A nameless erasure is a 400, never a wildcard. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(@JsonProperty("partyId") String partyId) {
}
