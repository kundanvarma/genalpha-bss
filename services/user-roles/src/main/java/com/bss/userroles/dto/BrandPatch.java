package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** PATCH myOperator: the three brand fields a hosted operator's own team may edit; anything else in the body is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandPatch(String name, String color, String tagline) {

    public boolean isEmpty() {
        return name == null && color == null && tagline == null;
    }
}
