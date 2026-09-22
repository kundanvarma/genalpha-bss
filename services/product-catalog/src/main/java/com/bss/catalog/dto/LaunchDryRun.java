package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Would this (possibly unsaved) offer launch by itself? The envelope dry-run's answer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"mode", "context", "preApproved", "envelope", "verdict"})
public record LaunchDryRun(String mode, LaunchContext context, boolean preApproved, EnvelopeRef envelope, String verdict) {
}
