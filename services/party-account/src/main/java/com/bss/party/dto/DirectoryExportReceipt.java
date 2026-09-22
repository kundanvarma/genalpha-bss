package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/** The run just made: its header, then the rows that shipped. */
@JsonPropertyOrder({"run", "rows"})
public record DirectoryExportReceipt(@JsonUnwrapped DirectoryExportRunView run, List<DirectoryListing> rows) {
}
