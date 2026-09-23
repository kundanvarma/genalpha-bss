package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The telesales channel: an offer, its written confirmation, the dial list, the partner's pipeline. */
public final class TelesalesDtos {

    private TelesalesDtos() {
    }

    /** The offer receipt; a COLD prospect's receipt carries the code the partner's own SMS sends. */
    @JsonPropertyOrder({"offerId", "status", "expiresAt", "confirmToken", "prospect"})
    public record OfferReceipt(String offerId, String status, String expiresAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String confirmToken,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean prospect) {
    }

    @JsonPropertyOrder({"status", "productOrderId"})
    public record ConfirmReceipt(String status, String productOrderId) {
    }

    @JsonPropertyOrder({"segment", "entries", "reservedExcluded", "unwashedExcluded"})
    public record DialList(String segment, List<DialEntry> entries, int reservedExcluded, int unwashedExcluded) {
    }

    @JsonPropertyOrder({"partyId", "name", "phone", "email", "consent"})
    public record DialEntry(String partyId, String name, String phone, String email, String consent) {
    }

    @JsonPropertyOrder({"id", "offeringName", "campaign", "status", "createdAt", "@type"})
    public record OfferRow(String id, String offeringName, String campaign, String status, String createdAt,
            @JsonProperty("@type") String type) {
    }

    /* ---------- requests ---------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfferRequest(String phone, String customerEmail, String prospectName, String campaign,
            String offeringId, String offeringName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmRequest(String token) {
    }
}
