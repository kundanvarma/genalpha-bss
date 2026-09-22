package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The DPO names the account to erase. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(String partyId) {
}
