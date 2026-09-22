package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * TMF672 User: a login in the caller's realm. The profile fields appear when
 * the IdP has them; {@code temporaryPassword} appears exactly once — on the
 * answer to the create that minted it — and never on a listing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "username", "email", "givenName", "familyName", "temporaryPassword", "@type"})
public record UserView(String id, String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) String givenName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String familyName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String temporaryPassword,
        @JsonProperty("@type") String type) {

    public static UserView of(IdpUser user) {
        return new UserView(user.id(), user.username(), user.email(), user.firstName(), user.lastName(), null, "User");
    }

    public static UserView created(String id, String email, String givenName, String familyName, String password) {
        return new UserView(id, email, email, givenName, familyName, password, "User");
    }
}
