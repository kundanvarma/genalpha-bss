package com.bss.intelligence.workforce;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The workforce scoreboard — every number computed from the ledger, with
 * its definition attached, and every ESTIMATE labeled as one:
 *
 *  - deflection = completed / (completed + escalated): what never reached
 *    a human
 *  - reopen rate: tickets a worker closed that are open again — the
 *    honesty metric that keeps the rest credible
 *  - human-minutes saved = completed × an OPERATOR-SET baseline per kind,
 *    shown next to the baselines so nobody mistakes an estimate for a
 *    measurement
 *  - cost is the worker's own word about its own model (self-reported),
 *    never conflated with the control plane's metered truth
 */
@Service
public class WorkforceKpiService {

    private final WorkforceTaskRepository tasks;
    private final WorkforceApprovalRepository approvals;
    private final WorkforceService workforce;
    private final com.bss.intelligence.audit.AiBudgetRepository budgets;
    private final BssApiClient bss;
    private final TenantScope tenantScope;
    private final long baselineTicketMinutes;
    private final long baselineCashMinutes;
    private final long surgeOpenPerWorker;

    public WorkforceKpiService(WorkforceTaskRepository tasks, WorkforceApprovalRepository approvals,
            WorkforceService workforce, com.bss.intelligence.audit.AiBudgetRepository budgets,
            BssApiClient bss, TenantScope tenantScope,
            @Value("${bss.workforce.baseline-minutes-ticket:12}") long baselineTicketMinutes,
            @Value("${bss.workforce.baseline-minutes-cash:8}") long baselineCashMinutes,
            @Value("${bss.workforce.surge-open-per-worker:10}") long surgeOpenPerWorker) {
        this.tasks = tasks;
        this.approvals = approvals;
        this.workforce = workforce;
        this.budgets = budgets;
        this.bss = bss;
        this.tenantScope = tenantScope;
        this.baselineTicketMinutes = baselineTicketMinutes;
        this.baselineCashMinutes = baselineCashMinutes;
        this.surgeOpenPerWorker = surgeOpenPerWorker;
    }

    @Transactional(readOnly = true)
    public WorkforceKpis kpis() {
        String tenant = tenantScope.currentTenantId();
        List<WorkforceTask> all = tasks.findTop200ByTenantIdOrderByLastUpdateDesc(tenant);

        long completed = 0;
        long escalated = 0;
        long handleSecondsSum = 0;
        long handleCount = 0;
        long selfCostMicros = 0;
        Map<String, long[]> byWorker = new TreeMap<>(); // completed, escalated, handleSecs, handleN, selfCost
        Map<String, java.util.Set<String>> workerKinds = new TreeMap<>();
        Map<String, OffsetDateTime> workerLastActive = new TreeMap<>();
        Map<String, Boolean> workerOnTask = new TreeMap<>();
        Map<String, long[]> byKind = new TreeMap<>();   // completed, escalated
        long minutesSaved = 0;
        long ticketChecked = 0;
        long ticketReopened = 0;
        OffsetDateTime now = OffsetDateTime.now();

        Map<String, String> workerNames = new TreeMap<>();
        for (WorkforceTask t : all) {
            String who = t.getClaimedBy() == null ? "?" : t.getClaimedBy();
            // every row counts toward WHO the worker is and WHEN it last moved
            if (t.getClaimedByName() != null) {
                workerNames.putIfAbsent(who, t.getClaimedByName());
            }
            workerKinds.computeIfAbsent(who, x -> new java.util.TreeSet<>()).add(t.getKind());
            if (t.getLastUpdate() != null) {
                workerLastActive.merge(who, t.getLastUpdate(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
            if (WorkforceTask.CLAIMED.equals(t.getStatus())
                    && t.getLeaseUntil() != null && t.getLeaseUntil().isAfter(now)) {
                workerOnTask.put(who, true); // holding a live lease = working NOW
            }
            boolean done = WorkforceTask.COMPLETED.equals(t.getStatus());
            boolean esc = WorkforceTask.ESCALATED.equals(t.getStatus());
            if (!done && !esc) {
                continue;
            }
            long[] w = byWorker.computeIfAbsent(who, k -> new long[5]);
            long[] k = byKind.computeIfAbsent(t.getKind(), x -> new long[2]);
            if (done) {
                completed++;
                w[0]++;
                k[0]++;
                minutesSaved += WorkforceService.KIND_TICKET.equals(t.getKind())
                        ? baselineTicketMinutes : baselineCashMinutes;
                if (t.getClaimedAt() != null && t.getCompletedAt() != null) {
                    long secs = Duration.between(t.getClaimedAt(), t.getCompletedAt()).getSeconds();
                    handleSecondsSum += secs;
                    handleCount++;
                    w[2] += secs;
                    w[3]++;
                }
                if (t.getSelfCostMicros() != null) {
                    selfCostMicros += t.getSelfCostMicros();
                    w[4] += t.getSelfCostMicros();
                }
                // the honesty metric: is the ticket the worker "completed"
                // open again? Checked live against the source, capped so the
                // dashboard read stays cheap.
                if (WorkforceTask.COMPLETED.equals(t.getStatus())
                        && WorkforceService.KIND_TICKET.equals(t.getKind()) && ticketChecked < 25) {
                    ticketChecked++;
                    com.fasterxml.jackson.databind.JsonNode ticket = bss.ticketById(t.getSubjectRef());
                    String status = ticket == null ? null : ticket.path("status").asText(null);
                    if ("inProgress".equals(status) || "acknowledged".equals(status)) {
                        ticketReopened++;
                    }
                }
            } else {
                escalated++;
                w[1]++;
                k[1]++;
            }
        }

        List<WorkforceApproval> aprs = approvals.findTop200ByTenantIdOrderByCreatedAtDesc(tenant);
        long pending = aprs.stream().filter(a -> WorkforceApproval.PENDING.equals(a.getStatus())).count();
        long approved = aprs.stream().filter(a -> WorkforceApproval.APPROVED.equals(a.getStatus())).count();
        long refused = aprs.stream().filter(a -> WorkforceApproval.REFUSED.equals(a.getStatus())).count();
        long decisionSecondsSum = 0;
        long decisionCount = 0;
        for (WorkforceApproval a : aprs) {
            if (a.getDecidedAt() != null && a.getCreatedAt() != null) {
                decisionSecondsSum += Duration.between(a.getCreatedAt(), a.getDecidedAt()).getSeconds();
                decisionCount++;
            }
        }

        WorkforceKpis.Reopen reopen = new WorkforceKpis.Reopen(ticketChecked, ticketReopened,
                ticketChecked == 0 ? 0.0 : Math.round(100.0 * ticketReopened / ticketChecked) / 100.0,
                "completed ticket tasks whose ticket is open again — checked live");
        Map<String, Long> baselines = new LinkedHashMap<>();
        baselines.put(WorkforceService.KIND_TICKET, baselineTicketMinutes);
        baselines.put(WorkforceService.KIND_CASH, baselineCashMinutes);
        WorkforceKpis.HumanMinutesSaved saved = new WorkforceKpis.HumanMinutesSaved(minutesSaved, true,
                baselines,
                "completed × operator-set baseline minutes per kind — an estimate, labeled as one");
        Map<String, WorkforceKpis.KindCount> kindCounts = new LinkedHashMap<>();
        byKind.forEach((kind, v) -> kindCounts.put(kind, new WorkforceKpis.KindCount(v[0], v[1])));
        // THE CREW: one row per worker — its TYPE derived from the kinds it
        // actually works (the ledger's word, not a self-description), whether
        // it holds a live lease right now, and its own numbers.
        Map<String, long[]> byType = new TreeMap<>(); // workers, workingNow, completed, escalated
        List<WorkforceKpis.WorkerRow> workers = new java.util.ArrayList<>();
        for (String who : workerKinds.keySet()) {
            long[] v = byWorker.getOrDefault(who, new long[5]);
            String type = typeOf(workerKinds.get(who));
            boolean working = workerOnTask.getOrDefault(who, false);
            workers.add(new WorkforceKpis.WorkerRow(who, workerNames.getOrDefault(who, who), type,
                    workerKinds.get(who), working, workerLastActive.get(who), v[0], v[1],
                    v[3] == 0 ? null : v[2] / v[3], v[4]));
            long[] tRow = byType.computeIfAbsent(type, x -> new long[4]);
            tRow[0]++;
            tRow[1] += working ? 1 : 0;
            tRow[2] += v[0];
            tRow[3] += v[1];
        }
        Map<String, WorkforceKpis.WorkerTypeRow> workerTypes = new TreeMap<>();
        for (Map.Entry<String, long[]> e : byType.entrySet()) {
            long[] t = e.getValue();
            long c = t[2];
            long esc = t[3];
            workerTypes.put(e.getKey(), new WorkforceKpis.WorkerTypeRow(t[0], t[1], c, esc,
                    c + esc == 0 ? null : Math.round(100.0 * c / (c + esc)) / 100.0));
        }
        return new WorkforceKpis(
                OffsetDateTime.now().toString(),
                completed,
                escalated,
                completed + escalated == 0 ? null
                        : Math.round(100.0 * completed / (completed + escalated)) / 100.0,
                handleCount == 0 ? null : handleSecondsSum / handleCount,
                reopen,
                selfCostMicros,
                "the workers' own word about their own models — not control-plane metered",
                saved,
                kindCounts,
                workers,
                workerTypes,
                workerOnTask.values().stream().filter(Boolean::booleanValue).count(),
                new WorkforceKpis.Approvals(pending, approved, refused,
                        decisionCount == 0 ? 0 : decisionSecondsSum / decisionCount),
                staffing());
    }

    /**
     * THE STAFFING SIGNAL: is the crew keeping up? Backlog depth over
     * active workers, against the operator's surge threshold — the number
     * an autoscaler (KEDA on the Prometheus gauge, or surge.sh in dev)
     * scales worker replicas on. The ceiling rides along so a scaler can
     * never aim past what the operator allowed.
     */
    public WorkforceKpis.Staffing staffing() {
        long backlog = workforce.deriveOpen().size();
        long active = workforce.activeWorkers().size();
        var budget = budgets.findByTenantId(tenantScope.currentTenantId()).orElse(null);
        int maxWorkers = budget == null ? 0 : budget.getMaxWorkers();
        Double openPerWorker = active == 0 ? null
                : Math.round(10.0 * backlog / active) / 10.0;
        boolean surge = active == 0 ? backlog > 0
                : backlog > surgeOpenPerWorker * active;
        return new WorkforceKpis.Staffing(backlog, active, openPerWorker, surge, surgeOpenPerWorker,
                maxWorkers, "surge = backlog exceeds threshold × active workers"
                        + " (or any backlog with zero workers); the ceiling caps the crew regardless");
    }

    /** A worker's type is what it WORKS, from the ledger: tickets → care,
     * cash → back-office, both → generalist. Observed, never self-declared. */
    private String typeOf(java.util.Set<String> kinds) {
        boolean care = kinds.contains(WorkforceService.KIND_TICKET);
        boolean cash = kinds.contains(WorkforceService.KIND_CASH);
        if (care && cash) {
            return "generalist";
        }
        if (cash) {
            return "back-office";
        }
        return "care";
    }
}
