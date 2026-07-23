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
    private final BssApiClient bss;
    private final TenantScope tenantScope;
    private final long baselineTicketMinutes;
    private final long baselineCashMinutes;

    public WorkforceKpiService(WorkforceTaskRepository tasks, WorkforceApprovalRepository approvals,
            BssApiClient bss, TenantScope tenantScope,
            @Value("${bss.workforce.baseline-minutes-ticket:12}") long baselineTicketMinutes,
            @Value("${bss.workforce.baseline-minutes-cash:8}") long baselineCashMinutes) {
        this.tasks = tasks;
        this.approvals = approvals;
        this.bss = bss;
        this.tenantScope = tenantScope;
        this.baselineTicketMinutes = baselineTicketMinutes;
        this.baselineCashMinutes = baselineCashMinutes;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> kpis() {
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

        for (WorkforceTask t : all) {
            String who = t.getClaimedBy() == null ? "?" : t.getClaimedBy();
            // every row counts toward WHO the worker is and WHEN it last moved
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
                    Map<String, Object> ticket = bss.ticketById(t.getSubjectRef());
                    String status = ticket == null ? null : String.valueOf(ticket.get("status"));
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", OffsetDateTime.now().toString());
        out.put("completed", completed);
        out.put("escalated", escalated);
        out.put("deflectionRate", completed + escalated == 0 ? null
                : Math.round(100.0 * completed / (completed + escalated)) / 100.0);
        out.put("avgHandleSeconds", handleCount == 0 ? null : handleSecondsSum / handleCount);
        out.put("reopen", Map.of(
                "checked", ticketChecked, "reopened", ticketReopened,
                "rate", ticketChecked == 0 ? 0.0
                        : Math.round(100.0 * ticketReopened / ticketChecked) / 100.0,
                "definition", "completed ticket tasks whose ticket is open again — checked live"));
        out.put("selfReportedCostMicros", selfCostMicros);
        out.put("selfReportedCostLabel",
                "the workers' own word about their own models — not control-plane metered");
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("minutes", minutesSaved);
        saved.put("estimate", true);
        saved.put("baselineMinutes", Map.of(
                WorkforceService.KIND_TICKET, baselineTicketMinutes,
                WorkforceService.KIND_CASH, baselineCashMinutes));
        saved.put("definition",
                "completed × operator-set baseline minutes per kind — an estimate, labeled as one");
        out.put("humanMinutesSaved", saved);
        out.put("byKind", byKind.entrySet().stream().collect(LinkedHashMap::new,
                (m, e) -> m.put(e.getKey(), Map.of(
                        "completed", e.getValue()[0], "escalated", e.getValue()[1])),
                LinkedHashMap::putAll));
        // THE CREW: one row per worker — its TYPE derived from the kinds it
        // actually works (the ledger's word, not a self-description), whether
        // it holds a live lease right now, and its own numbers.
        Map<String, Map<String, Object>> byType = new TreeMap<>();
        List<Map<String, Object>> workers = new java.util.ArrayList<>();
        for (String who : workerKinds.keySet()) {
            long[] v = byWorker.getOrDefault(who, new long[5]);
            String type = typeOf(workerKinds.get(who));
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("worker", who);
            w.put("type", type);
            w.put("kinds", workerKinds.get(who));
            w.put("workingNow", workerOnTask.getOrDefault(who, false));
            w.put("lastActiveAt", workerLastActive.get(who));
            w.put("completed", v[0]);
            w.put("escalated", v[1]);
            w.put("avgHandleSeconds", v[3] == 0 ? null : v[2] / v[3]);
            w.put("selfReportedCostMicros", v[4]);
            workers.add(w);

            Map<String, Object> tRow = byType.computeIfAbsent(type, x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("workers", 0L);
                m.put("workingNow", 0L);
                m.put("completed", 0L);
                m.put("escalated", 0L);
                return m;
            });
            tRow.put("workers", (Long) tRow.get("workers") + 1);
            tRow.put("workingNow", (Long) tRow.get("workingNow")
                    + (workerOnTask.getOrDefault(who, false) ? 1 : 0));
            tRow.put("completed", (Long) tRow.get("completed") + v[0]);
            tRow.put("escalated", (Long) tRow.get("escalated") + v[1]);
        }
        for (Map<String, Object> tRow : byType.values()) {
            long c = (Long) tRow.get("completed");
            long e = (Long) tRow.get("escalated");
            tRow.put("deflectionRate", c + e == 0 ? null
                    : Math.round(100.0 * c / (c + e)) / 100.0);
        }
        out.put("workers", workers);
        out.put("workerTypes", byType);
        out.put("workingNow", workerOnTask.values().stream().filter(Boolean::booleanValue).count());
        out.put("approvals", Map.of(
                "pending", pending, "approved", approved, "refused", refused,
                "avgDecisionSeconds", decisionCount == 0 ? 0 : decisionSecondsSum / decisionCount));
        return out;
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
