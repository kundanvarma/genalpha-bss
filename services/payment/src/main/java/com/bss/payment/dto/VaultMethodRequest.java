package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** What payment SENDS to the TMF670 vault when it saves a BNPL recurring token. */
@JsonPropertyOrder({"@type", "details", "relatedParty"})
public record VaultMethodRequest(
        @JsonProperty("@type") String type,
        @JsonProperty("details") Map<String, Object> details,
        @JsonProperty("relatedParty") List<RelatedPartyRef> relatedParty) {

    public static VaultMethodRequest bnplToken(String provider, String token, String ownerPartyId) {
        // declaration order, not Map.of — a wrapped Map.of is still random
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("brand", provider);
        details.put("token", token);
        return new VaultMethodRequest("bnplToken", details,
                List.of(RelatedPartyRef.customer(ownerPartyId)));
    }
}
