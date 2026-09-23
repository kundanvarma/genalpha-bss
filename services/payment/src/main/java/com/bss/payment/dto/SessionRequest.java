package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Open a redirect/BNPL session for one of the tenant's methods. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRequest(
        @JsonProperty("method") String method,
        @JsonProperty("amount") MoneyDto amount,
        @JsonProperty("returnUrl") String returnUrl) {
}
