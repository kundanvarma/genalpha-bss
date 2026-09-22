package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * TMF669 create/patch body. {@code roleType} is a {@link JsonNode} so a PATCH
 * can tell absent (leave alone) from an explicit null (clear).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartyRoleRequest(String name, String status, JsonNode roleType, EntityRef engagedParty) {

    public String engagedPartyId() {
        return engagedParty == null ? null : engagedParty.id();
    }

    public boolean hasRoleType() {
        return roleType != null && !roleType.isNull();
    }
}
