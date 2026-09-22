package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * What stock says about an offering: one row per stocked variant (the
 * characteristic values that make it, and how many are left). An offering the
 * warehouse does not manage is {@link #NONE} and never reaches the wire.
 */
@JsonPropertyOrder({"managed", "rows"})
public record Availability(boolean managed, List<Row> rows) {

    public static final Availability NONE = new Availability(false, List.of());

    @JsonPropertyOrder({"characteristics", "available"})
    public record Row(Map<String, String> characteristics, int available) {
    }
}
