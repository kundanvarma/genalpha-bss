package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** An offering named on a rail or a recommendation: id and name, nothing more. */
@JsonPropertyOrder({"id", "name"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfferRef(String id, String name) {

    /** The ranking gave no offering at all: an empty reference on the wire. */
    public static final OfferRef NONE = new OfferRef(null, null);
}
