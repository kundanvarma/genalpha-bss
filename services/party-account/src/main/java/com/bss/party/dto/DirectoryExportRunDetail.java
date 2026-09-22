package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** A past run read back: its header and the stored rows, verbatim. */
@JsonPropertyOrder({"run", "rows"})
public record DirectoryExportRunDetail(@JsonUnwrapped DirectoryExportRunView run, List<JsonNode> rows) {
}
