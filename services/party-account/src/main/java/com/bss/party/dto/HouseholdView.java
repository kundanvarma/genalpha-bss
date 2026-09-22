package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * The caller's household, both directions. The payer block is absent until a
 * payer exists and then written whole (a null {@code myRole} included); the
 * family list appears only for an active admin.
 */
@JsonPropertyOrder({"payerBlock", "dependents", "family"})
public record HouseholdView(
        @JsonUnwrapped PayerBlock payerBlock,
        List<Dependent> dependents,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Dependent> family) {

    @JsonPropertyOrder({"payer", "myRole"})
    public record PayerBlock(Payer payer, String myRole) {
    }

    @JsonPropertyOrder({"id", "status", "name"})
    public record Payer(String id, String status, @JsonInclude(JsonInclude.Include.NON_NULL) String name) {
    }

    @JsonPropertyOrder({"id", "givenName", "familyName", "status", "role", "topupAllowance"})
    public record Dependent(
            String id,
            String givenName,
            String familyName,
            String status,
            String role,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal topupAllowance) {
    }
}
