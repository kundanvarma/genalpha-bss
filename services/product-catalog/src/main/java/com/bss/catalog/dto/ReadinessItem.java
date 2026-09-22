package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One readiness tick: the owner authority ("campaign:write"), its label, and —
 * once somebody ticked it — who, when and with what note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"owner", "label", "done", "by", "at", "note"})
public record ReadinessItem(String owner, String label, boolean done, String by, String at, String note) {

    public static ReadinessItem open(String owner, String label) {
        return new ReadinessItem(owner, label, false, null, null, null);
    }

    public ReadinessItem ticked(boolean done, String by, String at, String note) {
        return new ReadinessItem(owner, label, done, by, at, note);
    }
}
