package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The deal engine's anonymous answer: base, the public rules that applied, the adjusted total. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"basePrice", "adjustments", "total", "indicative"})
public record IndicativePrice(BigDecimal basePrice, List<Map<String, Object>> adjustments, BigDecimal total,
        Boolean indicative, @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public IndicativePrice {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }
}
