package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The workforce scoreboard: every number computed from the ledger with its
 * definition attached, every estimate labeled as one. byKind and
 * workerTypes are keyed by the kind / type name the ledger observed.
 */
@JsonPropertyOrder({"asOf", "completed", "escalated", "deflectionRate", "avgHandleSeconds", "reopen",
        "selfReportedCostMicros", "selfReportedCostLabel", "humanMinutesSaved", "byKind", "workers",
        "workerTypes", "workingNow", "approvals", "staffing"})
public record WorkforceKpis(
        String asOf,
        long completed,
        long escalated,
        Double deflectionRate,
        Long avgHandleSeconds,
        Reopen reopen,
        long selfReportedCostMicros,
        String selfReportedCostLabel,
        HumanMinutesSaved humanMinutesSaved,
        Map<String, KindCount> byKind,
        List<WorkerRow> workers,
        Map<String, WorkerTypeRow> workerTypes,
        long workingNow,
        Approvals approvals,
        Staffing staffing) {

    /** The honesty metric: completed ticket tasks whose ticket is open again. */
    @JsonPropertyOrder({"checked", "reopened", "rate", "definition"})
    public record Reopen(long checked, long reopened, double rate, String definition) {
    }

    /** completed × an operator-set baseline per kind — an estimate, labeled. */
    @JsonPropertyOrder({"minutes", "estimate", "baselineMinutes", "definition"})
    public record HumanMinutesSaved(long minutes, boolean estimate, Map<String, Long> baselineMinutes,
            String definition) {
    }

    @JsonPropertyOrder({"completed", "escalated"})
    public record KindCount(long completed, long escalated) {
    }

    /** One worker on the crew: its type derived from what it works, whether
     * it holds a live lease right now, and its own numbers. */
    @JsonPropertyOrder({"worker", "workerName", "type", "kinds", "workingNow", "lastActiveAt",
            "completed", "escalated", "avgHandleSeconds", "selfReportedCostMicros"})
    public record WorkerRow(String worker, String workerName, String type, Set<String> kinds,
            boolean workingNow, OffsetDateTime lastActiveAt, long completed, long escalated,
            Long avgHandleSeconds, long selfReportedCostMicros) {
    }

    @JsonPropertyOrder({"workers", "workingNow", "completed", "escalated", "deflectionRate"})
    public record WorkerTypeRow(long workers, long workingNow, long completed, long escalated,
            Double deflectionRate) {
    }

    @JsonPropertyOrder({"pending", "approved", "refused", "avgDecisionSeconds"})
    public record Approvals(long pending, long approved, long refused, long avgDecisionSeconds) {
    }

    /** The staffing signal: backlog depth over active workers against the
     * operator's surge threshold; the ceiling rides along. */
    @JsonPropertyOrder({"backlogDepth", "activeWorkers", "openPerActiveWorker", "surge",
            "surgeThresholdOpenPerWorker", "maxWorkers", "definition"})
    public record Staffing(long backlogDepth, long activeWorkers, Double openPerActiveWorker,
            boolean surge, long surgeThresholdOpenPerWorker, int maxWorkers, String definition) {
    }
}
