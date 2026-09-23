package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Money that already arrived at the bank — remittance ingestion (OCR/KID, camt.054). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalPaymentRequest(
        @JsonProperty("amount") MoneyDto amount,
        @JsonProperty("correlatorId") String correlatorId,
        @JsonProperty("description") String description,
        @JsonProperty("reference") String reference,
        @JsonProperty("ownerPartyId") String ownerPartyId) {
}
