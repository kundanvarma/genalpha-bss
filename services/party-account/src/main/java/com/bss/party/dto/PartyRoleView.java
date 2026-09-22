package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/** TMF669 PartyRole as this component answers it; roleType is the caller's open block. */
@JsonPropertyOrder({"id", "href", "name", "status", "roleType", "engagedParty", "@type"})
public record PartyRoleView(
        String id,
        String href,
        String name,
        String status,
        JsonNode roleType,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef engagedParty,
        @JsonProperty("@type") String type) {
}
