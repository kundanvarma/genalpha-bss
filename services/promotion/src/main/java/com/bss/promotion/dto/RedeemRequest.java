package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The machine seam: order completion turns a code into an owner's discount. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RedeemRequest(String code, String relatedPartyId) {
}
