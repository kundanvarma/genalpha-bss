package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** An offering a subscription could move up to: same family, on sale on the caller's channel, dearer per month. */
@JsonPropertyOrder({"id", "name", "monthly", "family"})
public record UpgradeOption(String id, String name, BigDecimal monthly, String family) {
}
