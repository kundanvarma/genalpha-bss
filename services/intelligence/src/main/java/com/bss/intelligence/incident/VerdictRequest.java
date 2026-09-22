package com.bss.intelligence.incident;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The mandatory human verdict on a trace: useful or not, and why. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerdictRequest(Boolean useful, String note) {
}
