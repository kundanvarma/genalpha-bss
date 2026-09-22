package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** An outcome sweep, run now: how many receipts were judged for which tenant. */
@JsonPropertyOrder({"judged", "tenant"})
public record SweepResult(int judged, String tenant) {
}
