package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One technology the footprint can deliver at a place. */
@JsonPropertyOrder({"id", "state", "qualificationItemResult", "service"})
public record QueryItemView(String id, String state, String qualificationItemResult,
        ServiceView service) {

    public static QueryItemView of(int position, ServiceView service) {
        return new QueryItemView(String.valueOf(position), "done", "qualified", service);
    }
}
