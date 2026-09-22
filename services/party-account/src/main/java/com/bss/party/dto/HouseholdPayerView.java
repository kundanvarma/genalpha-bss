package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** Read-only projection on an individual: who pays for them, the link's state and role. */
@JsonPropertyOrder({"id", "status", "role", "topupAllowance"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record HouseholdPayerView(
        String id,
        String status,
        String role,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal topupAllowance) {
}
