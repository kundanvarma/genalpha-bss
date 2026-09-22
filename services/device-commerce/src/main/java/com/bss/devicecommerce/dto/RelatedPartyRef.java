package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The TMF related-party atom as this face writes it: the customer behind a row. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role"})
public record RelatedPartyRef(String id, String role) {

    public static RelatedPartyRef customer(String partyId) {
        return new RelatedPartyRef(partyId, "customer");
    }
}
