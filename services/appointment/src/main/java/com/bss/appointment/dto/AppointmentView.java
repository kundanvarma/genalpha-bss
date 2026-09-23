package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A TMF646 Appointment as this service answers it. {@code relatedEntity} and
 * {@code place} are the caller's own documents, kept verbatim in their JSON
 * columns — they are written even when empty (the map always put the key), so
 * NON_NULL rides only on the four keys the map used to leave off.
 */
@JsonPropertyOrder({"id", "href", "status", "description", "validFor", "relatedParty",
        "externalId", "provider", "relatedEntity", "place", "creationDate", "lastUpdate", "@type"})
public record AppointmentView(
        String id,
        String href,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        TimeWindow validFor,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) String externalId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
        JsonNode relatedEntity,
        JsonNode place,
        OffsetDateTime creationDate,
        OffsetDateTime lastUpdate) {

    @JsonProperty("@type")
    public String atType() {
        return "Appointment";
    }
}
