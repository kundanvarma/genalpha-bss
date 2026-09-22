package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** POST /audience/{id}/activate answers with a queued job, or the sandbox wall. */
public sealed interface ActivationResult permits ActivationResult.Queued, ActivationResult.Refused {

    @JsonPropertyOrder({"jobId", "mode", "destination", "status", "enabled"})
    record Queued(String jobId, String mode, String destination, String status, boolean enabled) implements ActivationResult {
    }

    /** activated=false: a clone's audiences never reach an ad platform. */
    @JsonPropertyOrder({"activated", "reason"})
    record Refused(boolean activated, String reason) implements ActivationResult {
    }
}
