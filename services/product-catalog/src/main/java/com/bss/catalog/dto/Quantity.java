package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** TMF Quantity: {amount, units} — a unit of measure, a charge period, a term length. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"amount", "units"})
public record Quantity(BigDecimal amount, String units,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public Quantity {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    public Quantity(BigDecimal amount, String units) {
        this(amount, units, null);
    }

    public Quantity(long amount, String units) {
        this(BigDecimal.valueOf(amount), units, null);
    }
}
