package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PATCH operator: the host admin's live mutation of a form-born operator.
 * Identity (id, issuer, key endpoints) is deliberately not here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperatorPatch(String name, String color, String locale, String currency, String catalogGovernance,
        String priceParityMode, String tagline, String agentCommerce) {

    public static OperatorPatch brand(BrandPatch brand) {
        return new OperatorPatch(brand.name(), brand.color(), null, null, null, null, brand.tagline(), null);
    }
}
