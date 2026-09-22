package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST user: the person to provision a login for. The role is never in the body — every new login is a customer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateUserRequest(String email, String givenName, String familyName) {
}
