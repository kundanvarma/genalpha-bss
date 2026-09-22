package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST permission: grant {@code userRole.name} to {@code user.id}, both references by the standard's names. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GrantRequest(UserRef user, RoleRef userRole) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRef(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleRef(String name) {
    }
}
