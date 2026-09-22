package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Which permission clause let the caller through ({@code by}), or why none did; {@code tried} lists every clause in words. */
@JsonPropertyOrder({"ok", "by", "says", "tried"})
public record PermissionVerdict(boolean ok, @JsonInclude(JsonInclude.Include.NON_NULL) String by, String says, List<String> tried) {
}
