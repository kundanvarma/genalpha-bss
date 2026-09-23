package com.bss.interaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The organisation whose desk handled the contact — the partner boundary.
 * Key order pinned to what the old image printed, not to the source's
 * {@code Map.of} declaration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"@referredType", "id"})
public record OrgRef(@JsonProperty("@referredType") String referredType, String id) {

    public static OrgRef of(String orgId) {
        return new OrgRef("Organization", orgId);
    }
}
