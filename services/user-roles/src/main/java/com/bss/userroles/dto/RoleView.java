package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF672 UserRole: a business role of the caller's realm (IdP plumbing roles are never listed). */
@JsonPropertyOrder({"name", "description", "@type"})
public record RoleView(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonProperty("@type") String type) {

    public static RoleView of(IdpRole role) {
        return new RoleView(role.name(), role.description(), "UserRole");
    }
}
