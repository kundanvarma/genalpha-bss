package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/** The bodies a badged worker or an approver posts. A record cannot carry a
 * field it does not declare. */
public final class WorkforceRequests {

    private WorkforceRequests() {
    }

    /**
     * What a worker spent on this task, in its own words.
     *
     * Top-level on purpose: it used to live inside CompleteTaskRequest, so a
     * worker could only declare its spend when it FINISHED something. The
     * worker escalates most of what it touches — 34 of 39 tasks in the 12 Oct
     * run — so the majority of spend had nowhere to be written down and the
     * cost ledger read as nearly free. The worker was not being silent; there
     * was no field to speak into.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SelfReport(Integer tokens, Long costMicros, String model) {
    }

    /** POST /tasks/{id}/complete — optional outcome and the worker's self-report. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompleteTaskRequest(String outcome, SelfReport selfReported) {
    }

    /** POST /tasks/{id}/escalate — a reason is required; the self-report is not,
     *  but giving up still costs tokens and the ledger should know. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EscalateRequest(String reason, SelfReport selfReported) {
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
