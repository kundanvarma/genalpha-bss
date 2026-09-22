package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A prospect and its consent state. */
@JsonPropertyOrder({"id", "email", "name", "phone", "source", "consent", "lawfulBasis", "@type"})
public record ProspectView(String id, String email, String name, String phone, String source, String consent,
        String lawfulBasis, @JsonProperty("@type") String type) {
}
