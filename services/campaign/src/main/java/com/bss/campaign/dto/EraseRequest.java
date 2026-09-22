package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The DPO names the person whose marketing shadow goes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EraseRequest(String partyId) {
}
