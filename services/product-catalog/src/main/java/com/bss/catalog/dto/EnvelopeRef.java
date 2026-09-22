package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The pre-approved envelope (a launch policy rule) an offer fell inside: {id, name[, message]}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "message"})
public record EnvelopeRef(String id, String name, String message) {

    public EnvelopeRef(String id, String name) {
        this(id, name, null);
    }
}
