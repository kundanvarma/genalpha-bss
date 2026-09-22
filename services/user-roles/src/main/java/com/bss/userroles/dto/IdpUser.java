package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The slice of an IdP's user representation this component reads; the rest of the vendor's document is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdpUser(String id, String username, String email, String firstName, String lastName) {
}
