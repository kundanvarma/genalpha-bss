package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A BNPL provider paid the merchant out: which provider, which payout, how much. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemittanceRequest(
        @JsonProperty("provider") String provider,
        @JsonProperty("reference") String reference,
        @JsonProperty("amount") MoneyRef amount) {
}
