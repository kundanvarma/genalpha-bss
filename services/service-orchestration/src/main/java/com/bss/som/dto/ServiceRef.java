package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A reference to a service: the standalone self-reference in a relationship
 * ({id, href}), the supporting service that says it supports itself
 * ({id, href, name, note}), a test's related service, a monitor's service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "note"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceRef(String id, String href, String name, String note) {

    public static ServiceRef of(String id) {
        return new ServiceRef(id, null, null, null);
    }

    public static ServiceRef at(String id, String href) {
        return new ServiceRef(id, href, null, null);
    }

    /** {id, href} pointing at the TMF638 inventory row. */
    public static ServiceRef inventory(String id) {
        return new ServiceRef(id, "/tmf-api/serviceInventory/v4/service/" + id, null, null);
    }
}
