package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A list import (an Excel paste, a purchased list, a lead-form sync). Consent is granted only by a lawful basis on the row. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectImport(String source, List<Row> prospects) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String email, String name, String phone, String source, String socialRef, String lawfulBasis) {
    }

    @JsonPropertyOrder({"imported", "updated", "reachable", "heldUnconsented"})
    public record Receipt(int imported, int updated, int reachable, int heldUnconsented) {
    }
}
