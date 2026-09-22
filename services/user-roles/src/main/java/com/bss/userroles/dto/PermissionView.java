package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Base64;

/**
 * TMF672 Permission: one (user, role) grant. Its id encodes the pair, so a
 * grant is an addressable REST resource that DELETE can revoke.
 */
@JsonPropertyOrder({"id", "user", "userRole", "@type"})
public record PermissionView(String id, UserRef user, RoleRef userRole, @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"id", "@referredType"})
    public record UserRef(String id, @JsonProperty("@referredType") String referredType) {
    }

    @JsonPropertyOrder({"name", "@referredType"})
    public record RoleRef(String name, @JsonProperty("@referredType") String referredType) {
    }

    public static PermissionView of(String userId, String roleName) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString((userId + "~" + roleName).getBytes());
        return new PermissionView(id, new UserRef(userId, "User"), new RoleRef(roleName, "UserRole"), "Permission");
    }
}
