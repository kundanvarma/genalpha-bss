package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/** A field installer on the tenant's roster — the rows window capacity derives from. */
@JsonPropertyOrder({"id", "name", "skills", "zone", "workingDays", "startTime", "endTime",
        "active", "creationDate", "lastUpdate", "@type"})
public record TechnicianView(
        String id,
        String name,
        List<String> skills,
        @JsonInclude(JsonInclude.Include.NON_NULL) String zone,
        List<String> workingDays,
        String startTime,
        String endTime,
        boolean active,
        OffsetDateTime creationDate,
        OffsetDateTime lastUpdate) {

    @JsonProperty("@type")
    public String atType() {
        return "Technician";
    }
}
