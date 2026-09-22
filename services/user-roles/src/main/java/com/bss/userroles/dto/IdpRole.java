package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The slice of an IdP's realm-role representation this component reads —
 * and writes back as a role mapping ({@code id} + {@code name} are what a
 * mapping needs). Vendor keys beyond these are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdpRole(String id, String name, String description) {
}
