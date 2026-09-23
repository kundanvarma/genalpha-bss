package com.bss.ticket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A trouble ticket on the wire (TMF621). One record serves the list, the read,
 * the create echo, the patch echo and the domain event — they were one map
 * before and they stay one shape now.
 *
 * <p>{@code relatedParty} is left off when nobody owns the ticket (social care
 * opens tickets for a handle, not a party) and {@code relatedEntity} when the
 * ticket points at nothing; every other key is written even when its value is
 * null, exactly as the map did.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "severity", "ticketType", "status",
        "relatedParty", "organization", "relatedEntity", "note",
        "creationDate", "statusChangeDate", "lastUpdate", "@type"})
public record TicketView(
        String id,
        String href,
        String name,
        String description,
        String severity,
        String ticketType,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        OrgRef organization,
        /** The caller's own document, stored and answered verbatim. */
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode relatedEntity,
        List<TicketNote> note,
        OffsetDateTime creationDate,
        OffsetDateTime statusChangeDate,
        OffsetDateTime lastUpdate) {

    @JsonProperty("@type")
    public String atType() {
        return "TroubleTicket";
    }
}
