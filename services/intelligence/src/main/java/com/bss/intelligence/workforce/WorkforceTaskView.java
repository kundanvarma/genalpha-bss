package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A ledgered task as the worker and the desk read it. */
@JsonPropertyOrder({"id", "kind", "subjectRef", "summary", "status", "claimedBy", "claimedByName",
        "claimedAt", "leaseUntil", "outcome", "selfReported", "completedAt"})
public record WorkforceTaskView(
        String id,
        String kind,
        String subjectRef,
        String summary,
        String status,
        String claimedBy,
        String claimedByName,
        OffsetDateTime claimedAt,
        OffsetDateTime leaseUntil,
        @JsonInclude(JsonInclude.Include.NON_NULL) String outcome,
        @JsonInclude(JsonInclude.Include.NON_NULL) SelfReported selfReported,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime completedAt) {

    /** The worker's own word about its own model — labeled, never conflated
     * with the control plane's metered truth. */
    @JsonPropertyOrder({"tokens", "costMicros", "model"})
    public record SelfReported(Integer tokens, Long costMicros, String model) {
    }

    static WorkforceTaskView of(WorkforceTask row) {
        SelfReported self = row.getSelfCostMicros() != null || row.getSelfTokens() != null
                ? new SelfReported(row.getSelfTokens(), row.getSelfCostMicros(), row.getSelfModel())
                : null;
        return new WorkforceTaskView(row.getId(), row.getKind(), row.getSubjectRef(), row.getSummary(),
                row.getStatus(), row.getClaimedBy(), row.getClaimedByName(), row.getClaimedAt(),
                row.getLeaseUntil(), row.getOutcome(), self, row.getCompletedAt());
    }
}
