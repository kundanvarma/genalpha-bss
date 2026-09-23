package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * The customer's terms. {@code agreementType} and {@code type} are the same
 * fact under two names (the fleet's and the spec's), and the engaged parties
 * answer under both {@code engagedParty} and {@code engagedPartyRole} — the
 * v3 kits and the partners each know one of them.
 *
 * <p>The three stored blocks are the caller's own documents, kept and
 * answered verbatim: an agreement's characteristic may be a map or a list,
 * and nothing here re-shapes it.
 */
@JsonPropertyOrder({"id", "href", "name", "agreementType", "type", "status",
        "agreementPeriod", "commitmentMonths", "engagedParty", "engagedPartyRole",
        "agreementItem", "characteristic", "lastUpdate", "@type"})
public record AgreementView(
        String id,
        String href,
        String name,
        String agreementType,
        String type,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgreementPeriod agreementPeriod,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer commitmentMonths,
        JsonNode engagedParty,
        JsonNode engagedPartyRole,
        JsonNode agreementItem,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode characteristic,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String jsonType) {

    public static AgreementView of(String id, String href, String name, String agreementType,
            String status, AgreementPeriod period, Integer commitmentMonths, JsonNode engaged,
            JsonNode items, JsonNode characteristic, OffsetDateTime lastUpdate) {
        return new AgreementView(id, href, name, agreementType, agreementType, status, period,
                commitmentMonths, engaged, engaged, items, characteristic, lastUpdate,
                "Agreement");
    }
}
