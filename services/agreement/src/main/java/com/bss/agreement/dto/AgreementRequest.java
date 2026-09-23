package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted agreement. The five blocks the service stores or walks stay trees:
 * {@code characteristic} is legally a map OR a list, the party lists arrive
 * under two names, and {@code commitmentMonths} is honoured only when it is a
 * real JSON number — a posted "12" was ignored by the map path and still is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgreementRequest(
        String name,
        String type,
        String agreementType,
        String status,
        JsonNode engagedParty,
        JsonNode engagedPartyRole,
        JsonNode agreementItem,
        JsonNode characteristic,
        JsonNode agreementPeriod,
        JsonNode commitmentMonths) {

    /** `type` (the spec's name) and `agreementType` (the fleet's) are one fact. */
    public String resolvedType() {
        return agreementType != null ? agreementType : type;
    }

    /** v3 kits and partners say engagedPartyRole; the fleet says engagedParty. */
    public JsonNode resolvedParties() {
        if (engagedParty != null && !engagedParty.isNull()) {
            return engagedParty;
        }
        return engagedPartyRole != null && engagedPartyRole.isArray() ? engagedPartyRole : null;
    }

    public static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }
}
