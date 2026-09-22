package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step of a stepped overage table: units beyond the allowance from
 * {@code valueFrom} to {@code valueTo} (open-ended when absent) at
 * {@code price} per unit (the allowance's flat rate when absent). The shape
 * is ours — the SOM pushes the same steps to the charging system — and it is
 * stored on the allowance as the JSON array it always was.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"valueFrom", "valueTo", "price"})
public record Tier(BigDecimal valueFrom, BigDecimal valueTo, BigDecimal price,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public Tier {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    public static Tier of(BigDecimal valueFrom, BigDecimal valueTo, BigDecimal price) {
        return new Tier(valueFrom, valueTo, price, null);
    }
}
