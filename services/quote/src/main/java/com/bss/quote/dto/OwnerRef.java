package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Who owns a lead or a deal — the rep it routed to, by id when known and by name. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name"})
public record OwnerRef(String id, String name) {

    /** null when neither an id nor a name is known — the key is then left off. */
    public static OwnerRef ofNullable(String id, String name) {
        return id == null && name == null ? null : new OwnerRef(id, name);
    }
}
