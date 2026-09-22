package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Operator-as-a-form: the identity and brand of the operator to mint. Only {@code id} is required. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardRequest(String id, String name, String locale, String currency, String color) {

    public static OnboardRequest of(String id, String name, String locale, String currency, String color) {
        return new OnboardRequest(id, name, locale, currency, color);
    }
}
