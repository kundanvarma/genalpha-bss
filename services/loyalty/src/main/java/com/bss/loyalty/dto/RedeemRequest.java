package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /redeem body: {type:"data", gb:N} or {type:"voucher"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RedeemRequest(String type, Integer gb) {

    public int gbOrDefault() {
        return gb == null ? 1 : gb;
    }
}
