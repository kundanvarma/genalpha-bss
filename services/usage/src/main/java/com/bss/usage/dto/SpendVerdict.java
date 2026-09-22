package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The accrue seam's answer: may the charge stand, and the meters it touched. */
@JsonPropertyOrder({"accepted", "meter"})
public record SpendVerdict(boolean accepted, List<SpendMeterView> meter) {
}
