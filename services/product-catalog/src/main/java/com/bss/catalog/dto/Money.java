package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** TMF Money: {unit, value}. Anything else a client sends rides along in {@code extensions}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"unit", "value"})
public record Money(String unit, BigDecimal value,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public Money {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    public Money(String unit, BigDecimal value) {
        this(unit, value, null);
    }

    /** The currency, or the fleet default when the price does not say. */
    public String unitOr(String dflt) {
        return unit == null ? dflt : unit;
    }

    /**
     * From an open object at the edge — a federated legacy ref embeds its price
     * on the ref itself. An unreadable value reads as absent; other keys ride along.
     */
    public static Money of(Map<?, ?> raw) {
        String unit = raw.get("unit") == null ? null : String.valueOf(raw.get("unit"));
        BigDecimal value;
        try {
            value = raw.get("value") == null ? null : new BigDecimal(String.valueOf(raw.get("value")));
        } catch (NumberFormatException e) {
            value = null;
        }
        Map<String, Object> rest = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (!"unit".equals(k) && !"value".equals(k)) {
                rest.put(String.valueOf(k), v);
            }
        });
        return new Money(unit, value, rest);
    }
}
