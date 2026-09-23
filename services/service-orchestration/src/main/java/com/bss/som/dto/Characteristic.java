package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF characteristic; the value stays open — that is the edge. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "valueType", "value"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record Characteristic(String name, String valueType, Object value) {

    public static Characteristic string(String name, String value) {
        return new Characteristic(name, "string", value);
    }

    public static Characteristic dateTime(String name, String value) {
        return new Characteristic(name, "dateTime", value);
    }

    public String text() {
        return value == null ? null : String.valueOf(value);
    }
}
