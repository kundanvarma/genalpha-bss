package com.bss.ticket.dto;

import com.bss.ticket.api.Json;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST troubleTicket. The body is a record so it cannot carry a field the
 * service never declared (no mass assignment: status, owner, org and tenant
 * come from the token and the store). Every component stays an open node
 * because TMF621 bodies arrive leniently and the door has always read them
 * leniently: a number is a fine {@code ticketType}, a nested block prints as
 * Java, and a note that is not a list is the 500 it always was.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TroubleTicketCreateRequest(
        JsonNode name,
        JsonNode description,
        JsonNode ticketType,
        JsonNode severity,
        JsonNode note,
        JsonNode relatedParty,
        JsonNode relatedEntity) {

    /** The customer named in the body, for an agent-raised ticket. */
    @JsonIgnore
    public String customerPartyId() {
        if (relatedParty == null || !relatedParty.isArray()) {
            return null;
        }
        for (JsonNode ref : relatedParty) {
            if (ref.isObject() && "customer".equalsIgnoreCase(Json.valueOfLike(ref.get("role")))) {
                // A customer reference without an id has always stored the
                // literal "null" as the owner — the map minted it and the
                // ticket is visible to staff only, as before.
                return Json.valueOfLike(ref.get("id"));
            }
        }
        return null;
    }
}
