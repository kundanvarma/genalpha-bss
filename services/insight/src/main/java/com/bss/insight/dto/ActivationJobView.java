package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** An activation job as a poller sees it: queued -> running -> done|error, with counts. */
@JsonPropertyOrder({"jobId", "audienceId", "externalAudienceId", "mode", "destination", "status", "members", "pushed",
        "skipped", "error", "finishedAt"})
public record ActivationJobView(String jobId, String audienceId, String externalAudienceId, String mode, String destination,
        String status, Integer members, Integer pushed, Integer skipped,
        @JsonInclude(JsonInclude.Include.NON_NULL) String error, OffsetDateTime finishedAt) {
}
