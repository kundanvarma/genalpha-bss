package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A poll-mode sync: what came in, what was already there. */
@JsonPropertyOrder({"connector", "ingested", "duplicates"})
public record ConnectorSyncReceipt(String connector, int ingested, int duplicates) {
}
