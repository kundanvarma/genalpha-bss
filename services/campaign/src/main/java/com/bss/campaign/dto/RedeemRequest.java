package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A joiner redeems a friend's code — optionally for the street's unlock game. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RedeemRequest(String code, String areaCode) {
}
