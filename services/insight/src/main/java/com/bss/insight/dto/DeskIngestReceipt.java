package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** How many desk events were accepted; enabled=false when the tenant switched desk learning off. */
@JsonPropertyOrder({"accepted", "enabled"})
public record DeskIngestReceipt(int accepted, boolean enabled) {
}
