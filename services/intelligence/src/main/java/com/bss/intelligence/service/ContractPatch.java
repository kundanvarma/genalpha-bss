package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The in-life lever on one contract: {state: suspended|active, note}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractPatch(String state, String note) {
}
