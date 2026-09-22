package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** The bodies a badged worker or an approver posts. A record cannot carry a
 * field it does not declare. */
public final class WorkforceRequests {

    private WorkforceRequests() {
    }

    /** POST /tasks/{id}/complete — optional outcome and the worker's self-report. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompleteTaskRequest(String outcome, SelfReport selfReported) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SelfReport(Integer tokens, Long costMicros, String model) {
        }
    }

    /** POST /tasks/{id}/escalate — a reason is required. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EscalateRequest(String reason) {
    }

    /** POST /approvals — the action filed as data; the body is the caller's
     * JSON kept verbatim for the approver's own token to send. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileApprovalRequest(String action, String method, String path, String reason,
            JsonNode body) {
    }

    /** POST /approvals/{id}/approve|refuse — an optional note. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecisionNote(String note) {
    }
}
