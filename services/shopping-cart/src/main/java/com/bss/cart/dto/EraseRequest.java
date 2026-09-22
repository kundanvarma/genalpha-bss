package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The DPO names the party to erase. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(String partyId) {
}
