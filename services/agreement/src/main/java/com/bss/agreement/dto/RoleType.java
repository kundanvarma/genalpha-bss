package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One role a partnership type permits. A role IS its name. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "description", "@type"})
public record RoleType(
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonProperty("@type") String type) {

    public static RoleType of(String name, String description) {
        return new RoleType(name, description, "PartnerRoleType");
    }
}
