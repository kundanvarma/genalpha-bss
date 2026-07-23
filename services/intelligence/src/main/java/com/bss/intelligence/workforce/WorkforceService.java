package com.bss.intelligence.workforce;

import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The digital workforce: a task queue DERIVED live from the backlogs that
 * already exist (unassigned tickets, unapplied cash) — never a copy that
 * can go stale — plus a claim/complete/escalate lifecycle ledgered in
 * workforce_task. Lease semantics on claim (a crashed worker's task frees
 * itself); VERIFIED completion where cheap (a ticket task completes only
 * when the ticket is actually resolved; a cash task only when the row has
 * left the worklist) — a worker cannot mark done what is not done.
 *
 * The tenant's AI kill-switch governs this API too: one lever stops the
 * copilots AND the workers.
 */
@Service
public class WorkforceService {

    public static final String KIND_TICKET = "ticket";
    public static final String KIND_CASH = "unapplied-cash";

    private final WorkforceTaskRepository tasks;
    private final AiBudgetRepository budgets;
    private final BssApiClient bss;
    private final TenantScope tenantScope;
    private final long leaseSeconds;

    public WorkforceService(WorkforceTaskRepository tasks, AiBudgetRepository budgets,
            BssApiClient bss, TenantScope tenantScope,
            @Value("${bss.workforce.lease-seconds:900}") long leaseSeconds) {
        this.tasks = tasks;
        this.budgets = budgets;
        this.bss = bss;
        this.tenantScope = tenantScope;
        this.leaseSeconds = leaseSeconds;
    }

    /** The open queue: every backlog item not currently claimed or already
     * worked. Recomputed on every read — the sources are the truth. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openTasks() {
        requireEnabled();
        String tenant = tenantScope.currentTenantId();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> candidate : derive()) {
            Optional<WorkforceTask> row = tasks.findByIdAndTenantId(
                    String.valueOf(candidate.get("id")), tenant);
            boolean taken = row.isPresent() && (isDone(row.get()) || leaseActive(row.get()));
            if (!taken) {
                open.add(candidate);
            }
        }
        return open;
    }

    @Transactional
    public Map<String, Object> claim(String taskId) {
        requireEnabled();
        String tenant = tenantScope.currentTenantId();
        Map<String, Object> candidate = derive().stream()
                .filter(c -> taskId.equals(c.get("id"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no such open task — the backlog item is gone or never existed"));
        WorkforceTask row = tasks.findByIdAndTenantId(taskId, tenant).orElseGet(WorkforceTask::new);
        if (row.getId() != null && isDone(row)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task is already " + row.getStatus());
        }
        String caller = callerId();
        if (row.getId() != null && leaseActive(row) && !caller.equals(row.getClaimedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "task is claimed by another worker until " + row.getLeaseUntil());
        }
        row.setId(taskId);
        row.setTenantId(tenant);
        row.setKind(String.valueOf(candidate.get("kind")));
        row.setSubjectRef(String.valueOf(candidate.get("subjectRef")));
        row.setSummary(String.valueOf(candidate.get("summary")));
        row.setStatus(WorkforceTask.CLAIMED);
        row.setClaimedBy(caller);
        row.setClaimedAt(OffsetDateTime.now());
        row.setLeaseUntil(OffsetDateTime.now().plusSeconds(leaseSeconds));
        row.setLastUpdate(OffsetDateTime.now());
        return view(tasks.save(row));
    }

    @Transactional
    public Map<String, Object> complete(String taskId, Map<String, Object> body) {
        requireEnabled();
        WorkforceTask row = requireMyClaim(taskId);
        verifyDone(row);
        row.setStatus(WorkforceTask.COMPLETED);
        row.setOutcome(body == null ? null : str(body.get("outcome")));
        applySelfReport(row, body);
        row.setCompletedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        return view(tasks.save(row));
    }

    @Transactional
    public Map<String, Object> escalate(String taskId, Map<String, Object> body) {
        requireEnabled();
        WorkforceTask row = requireMyClaim(taskId);
        String reason = body == null ? null : str(body.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a reason is required — an escalation without one helps nobody");
        }
        row.setStatus(WorkforceTask.ESCALATED);
        row.setOutcome(reason);
        row.setCompletedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        return view(tasks.save(row));
    }

    /** The shift ledger — what the workforce worked, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ledger() {
        requireEnabled();
        return tasks.findTop200ByTenantIdOrderByLastUpdateDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    /* ---------- queue derivation ---------- */

    private List<Map<String, Object>> derive() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ticket : bss.unworkedTickets()) {
            String id = String.valueOf(ticket.get("id"));
            out.add(task(KIND_TICKET, id,
                    "[" + ticket.getOrDefault("severity", "-") + "] "
                            + ticket.getOrDefault("name", "trouble ticket")));
        }
        for (Map<String, Object> cash : bss.unappliedCash()) {
            String id = String.valueOf(cash.get("id"));
            Object amount = cash.get("amount");
            out.add(task(KIND_CASH, id,
                    "unapplied " + (amount == null ? "payment" : amount) + " — "
                            + cash.getOrDefault("reason", "")));
        }
        return out;
    }

    private Map<String, Object> task(String kind, String subjectRef, String summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", kind + "~" + subjectRef);
        map.put("kind", kind);
        map.put("subjectRef", subjectRef);
        map.put("summary", summary.length() > 490 ? summary.substring(0, 490) : summary);
        return map;
    }

    /* ---------- completion verification ---------- */

    private void verifyDone(WorkforceTask row) {
        if (KIND_TICKET.equals(row.getKind())) {
            Map<String, Object> ticket = bss.ticketById(row.getSubjectRef());
            String status = ticket == null ? null : String.valueOf(ticket.get("status"));
            if (ticket != null && !"resolved".equals(status) && !"closed".equals(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "the ticket is still '" + status
                                + "' — finish the work before completing the task");
            }
        } else if (KIND_CASH.equals(row.getKind())) {
            boolean stillParked = bss.unappliedCash().stream()
                    .anyMatch(r -> row.getSubjectRef().equals(String.valueOf(r.get("id"))));
            if (stillParked) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "the payment is still on the unapplied worklist — apply it before "
                                + "completing the task");
            }
        }
    }

    /* ---------- plumbing ---------- */

    private void requireEnabled() {
        AiBudget budget = budgets.findByTenantId(tenantScope.currentTenantId()).orElse(null);
        if (budget != null && !budget.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI is disabled for this tenant — the workforce rests with the copilots");
        }
    }

    private WorkforceTask requireMyClaim(String taskId) {
        WorkforceTask row = tasks.findByIdAndTenantId(taskId, tenantScope.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no such claimed task"));
        if (isDone(row)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "task is already " + row.getStatus());
        }
        if (!callerId().equals(row.getClaimedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "task is claimed by another worker");
        }
        return row;
    }

    private boolean isDone(WorkforceTask row) {
        return WorkforceTask.COMPLETED.equals(row.getStatus())
                || WorkforceTask.ESCALATED.equals(row.getStatus());
    }

    private boolean leaseActive(WorkforceTask row) {
        return WorkforceTask.CLAIMED.equals(row.getStatus())
                && row.getLeaseUntil() != null
                && row.getLeaseUntil().isAfter(OffsetDateTime.now());
    }

    private void applySelfReport(WorkforceTask row, Map<String, Object> body) {
        Object self = body == null ? null : body.get("selfReported");
        if (self instanceof Map<?, ?> m) {
            if (m.get("tokens") != null) {
                row.setSelfTokens(Integer.parseInt(String.valueOf(m.get("tokens"))));
            }
            if (m.get("costMicros") != null) {
                row.setSelfCostMicros(Long.parseLong(String.valueOf(m.get("costMicros"))));
            }
            if (m.get("model") != null) {
                row.setSelfModel(String.valueOf(m.get("model")));
            }
        }
    }

    private Map<String, Object> view(WorkforceTask row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.getId());
        map.put("kind", row.getKind());
        map.put("subjectRef", row.getSubjectRef());
        map.put("summary", row.getSummary());
        map.put("status", row.getStatus());
        map.put("claimedBy", row.getClaimedBy());
        map.put("claimedAt", row.getClaimedAt());
        map.put("leaseUntil", row.getLeaseUntil());
        if (row.getOutcome() != null) {
            map.put("outcome", row.getOutcome());
        }
        if (row.getSelfCostMicros() != null || row.getSelfTokens() != null) {
            // the worker's own word about its own model — labeled, never
            // conflated with the control plane's metered truth
            Map<String, Object> self = new LinkedHashMap<>();
            self.put("tokens", row.getSelfTokens());
            self.put("costMicros", row.getSelfCostMicros());
            self.put("model", row.getSelfModel());
            map.put("selfReported", self);
        }
        if (row.getCompletedAt() != null) {
            map.put("completedAt", row.getCompletedAt());
        }
        return map;
    }

    private String callerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
