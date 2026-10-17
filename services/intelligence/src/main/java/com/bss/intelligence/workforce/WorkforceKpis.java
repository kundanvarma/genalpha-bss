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
        "workerTypes", "workingNow", "approvals", "staffing", "measuredCostMicros", "measuredCostLabel"})
public record WorkforceKpis(
        String asOf,
        long completed,
        long escalated,
        Double deflectionRate,
        Long avgHandleSeconds,
        Reopen reopen,
        long selfReportedCostMicros,
        String selfReportedCostLabel,
        CostCoverage costCoverage,
        HumanMinutesSaved humanMinutesSaved,
        Map<String, KindCount> byKind,
        List<WorkerRow> workers,
        Map<String, WorkerTypeRow> workerTypes,
        long workingNow,
        Approvals approvals,
        Staffing staffing,
        // MEASURED (#283): what the workers' model calls cost, as the model door recorded
        // them from the provider's own token counts — micro-dollars, the provider's currency
        long measuredCostMicros,
        String measuredCostLabel) {

    /**
     * HOW MUCH OF THE COST FIGURE IS ACTUALLY MEASURED.
     *
     * The cost above is the worker's own word, posted on completion, and a
     * worker that says nothing is simply not counted. On 12 Oct one task in
     * thirty-nine reported anything at all, so the total read $0.000005 for two
     * hours of real model work. The number was not wrong so much as nearly
     * empty, and nothing on the payload said so.
     *
     * A reader can now see the denominator and judge the figure themselves.
     */
    @JsonPropertyOrder({"reported", "ofTasks", "definition"})
    public record CostCoverage(long reported, long ofTasks, String definition) {
    }

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
            "completed", "escalated", "avgHandleSeconds", "selfReportedCostMicros", "measuredCostMicros"})
    public record WorkerRow(String worker, String workerName, String type, Set<String> kinds,
            boolean workingNow, OffsetDateTime lastActiveAt, long completed, long escalated,
            Long avgHandleSeconds, long selfReportedCostMicros, long measuredCostMicros) {
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
            "surgeThresholdOpenPerWorker", "maxWorkers", "definition", "workerBudgetMicros"})
    public record Staffing(long backlogDepth, long activeWorkers, Double openPerActiveWorker,
            boolean surge, long surgeThresholdOpenPerWorker, int maxWorkers, String definition,
            // the workers' own spend ceiling at the model door (#283), micro-dollars; 0 = none
            long workerBudgetMicros) {
    }
}
