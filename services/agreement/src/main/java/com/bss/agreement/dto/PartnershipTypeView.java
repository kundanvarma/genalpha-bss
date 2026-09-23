package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/** A partnership kind as tenant catalog data. */
@JsonPropertyOrder({"id", "href", "name", "description", "status", "roleType",
        "lastUpdate", "@type"})
public record PartnershipTypeView(
        String id,
        String href,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        String status,
        List<RoleType> roleType,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static PartnershipTypeView of(String id, String href, String name, String description,
            String status, List<RoleType> roleTypes, OffsetDateTime lastUpdate) {
        return new PartnershipTypeView(id, href, name, description, status, roleTypes,
                lastUpdate, "PartnershipType");
    }
}
