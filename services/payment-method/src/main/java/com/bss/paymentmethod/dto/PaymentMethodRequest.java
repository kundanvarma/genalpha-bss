package com.bss.paymentmethod.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted method. The vault token, the owner and the status are never taken
 * from the body — the owner comes from the token or the named party, the
 * status from the service, and the vault token is minted here unless the
 * provider already owns one (a BNPL recurring token).
 *
 * <p>{@code details} and {@code preferred} stay trees because the map path
 * read them leniently: a non-object details was refused by the service's own
 * message, and {@code Boolean.TRUE.equals} meant the string "true" never set
 * a method preferred.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentMethodRequest(
        @JsonProperty("@type") String type,
        JsonNode details,
        JsonNode preferred,
        JsonNode relatedParty) {

    public boolean preferredOrFalse() {
        return preferred != null && preferred.isBoolean() && preferred.booleanValue();
    }

    /** The id of the first party named that has one — exactly what the map read. */
    public String firstPartyId() {
        if (relatedParty == null || !relatedParty.isArray()) {
            return null;
        }
        for (JsonNode ref : relatedParty) {
            JsonNode id = ref.isObject() ? ref.get("id") : null;
            if (id != null && !id.isNull()) {
                return id.asText();
            }
        }
        return null;
    }

    public String detail(String field) {
        JsonNode value = details == null ? null : details.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
