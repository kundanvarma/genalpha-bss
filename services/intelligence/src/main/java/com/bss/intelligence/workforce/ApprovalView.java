package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** A filed approval as the desk reads it: the stored request, who asked,
 * who decided. body and result are the caller's and the downstream's JSON,
 * kept verbatim. */
@JsonPropertyOrder({"id", "action", "method", "path", "body", "reason", "status", "requestedBy",
        "requestedByName", "createdAt", "decidedBy", "decidedByName", "decidedAt", "decisionNote",
        "result"})
public record ApprovalView(
        String id,
        String action,
        String method,
        String path,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode body,
        String reason,
        String status,
        String requestedBy,
        String requestedByName,
        OffsetDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decidedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decidedByName,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime decidedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decisionNote,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode result) {
}
