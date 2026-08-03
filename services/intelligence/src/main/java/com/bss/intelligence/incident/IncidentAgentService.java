package com.bss.intelligence.incident;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The process incident agent, L0: a failed taskFlow triggers CONTEXT
 * ASSEMBLY (design intent + the failed step + the flow's cross-system
 * timeline), a GOVERNED diagnosis (killed/budgeted/metered/audited like
 * every AI call), and exactly one write — a ticket note. Every
 * investigation becomes an EPISODIC TRACE with a mandatory human
 * verdict: memory first, autonomy later.
 */
@Service
public class IncidentAgentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentAgentService.class);

    static final String SYSTEM_PROMPT = """
            You are a process incident diagnostician for a telecom BSS.
            You are given the assembled context of ONE failed process task:
            the design intent (the flow's specification), the failed step
            (what it owed and by when), and the cross-system event timeline.
            Diagnose the most likely cause. Answer EXACTLY in this format:
            DIAGNOSIS: <one or two sentences, concrete, no speculation beyond the evidence>
            CONFIDENCE: <0.0-1.0>
            ACTION: <the single next step a human operator should take>""";

    static final int PROMOTION_THRESHOLD = 3;

    private final IncidentTraceRepository traces;
    private final IncidentRunbookRepository runbooks;
    private final BssApiClient bss;
    private final AiGovernor governor;
    private final TenantScope tenantScope;

    public IncidentAgentService(IncidentTraceRepository traces, IncidentRunbookRepository runbooks,
            BssApiClient bss, AiGovernor governor, TenantScope tenantScope) {
        this.traces = traces;
        this.runbooks = runbooks;
        this.bss = bss;
        this.governor = governor;
        this.tenantScope = tenantScope;
    }

    /* ---------- the investigation (listener-called, acting as tenant) ---------- */

    @Transactional
    public void onTaskFailed(Map<String, Object> event) {
        String tenant = tenantScope.currentTenantId();
        String flowId = String.valueOf(event.get("processFlowId"));
        if ("null".equals(flowId) || traces.existsByTenantIdAndProcessFlowId(tenant, flowId)) {
            return; // one investigation per flow; at-least-once delivery is free
        }
        String specCode = String.valueOf(event.getOrDefault("specCode", "unknown"));
        String taskCode = String.valueOf(event.getOrDefault("taskCode", "unknown"));
        String signature = specCode + ":" + taskCode;
        long started = System.currentTimeMillis();

        // WORKING CONTEXT: design intent + the failed step + the timeline
        String context = assembleContext(specCode, taskCode, flowId, event);

        IncidentTrace trace = new IncidentTrace();
        trace.setId(UUID.randomUUID().toString());
        trace.setTenantId(tenant);
        trace.setSignature(signature);
        trace.setProcessFlowId(flowId);
        trace.setProductOrderId(str(event.get("productOrderId")));
        trace.setSpecCode(specCode);
        trace.setTaskCode(taskCode);
        trace.setPartyId(partyOf(event));
        trace.setContextDigest(clip(context, 3900));
        trace.setSource("llm");
        trace.setVerdict("pending");
        trace.setCreatedAt(OffsetDateTime.now());

        // PROCEDURAL MEMORY FIRST: an approved runbook diagnoses on sight —
        // no LLM call, no model spend, and the audit trail says so
        IncidentRunbook approved = runbooks
                .findFirstByTenantIdAndSignatureAndStatusOrderByVersionDesc(
                        tenant, signature, IncidentRunbook.APPROVED).orElse(null);
        if (approved != null) {
            trace.setSource("runbook");
            trace.setHypothesis("[runbook " + signature + " v" + approved.getVersion() + "] "
                    + approved.getDiagnosis());
            trace.setProposedAction(approved.getAction());
            trace.setConfidence(new BigDecimal("0.95"));
        } else {
            // the GOVERNED diagnosis — same doors as every other AI call
            try {
                String answer = governor.complete("incident-diagnosis",
                        LlmAdapter.Tier.SMART, SYSTEM_PROMPT, context);
                trace.setHypothesis(clip(section(answer, "DIAGNOSIS"), 1900));
                trace.setConfidence(confidenceOf(answer));
                trace.setProposedAction(clip(section(answer, "ACTION"), 900));
            } catch (Exception e) {
                trace.setHypothesis("diagnosis unavailable: " + clip(e.getMessage(), 200));
                trace.setConfidence(BigDecimal.ZERO);
            }
        }
        trace.setDiagnoseMs(System.currentTimeMillis() - started);

        // L0: the ONLY write is a ticket note — the machine is the author
        String ticketId = bss.openTicket(
                "Order flow failed: " + signature + " (order "
                        + trace.getProductOrderId() + ")",
                String.valueOf(event.getOrDefault("message", "process task failed")),
                trace.getPartyId());
        if (ticketId != null) {
            bss.addTicketNote(ticketId, "AGENT DIAGNOSIS ("
                    + ("runbook".equals(trace.getSource())
                        ? "AUTO, from approved runbook — no model call"
                        : "L0, read-only") + " — confidence "
                    + trace.getConfidence() + "):\n" + trace.getHypothesis()
                    + (trace.getProposedAction() == null ? ""
                        : "\nPROPOSED ACTION (human executes): " + trace.getProposedAction()));
            trace.setTicketId(ticketId);
        }
        traces.save(trace);
        log.info("incident: {} diagnosed in {}ms (flow {}, ticket {})",
                signature, trace.getDiagnoseMs(), flowId, ticketId);
    }

    private String assembleContext(String specCode, String taskCode, String flowId,
            Map<String, Object> event) {
        StringBuilder ctx = new StringBuilder();
        Map<String, Object> spec = bss.processSpecs().stream()
                .filter(s -> specCode.equals(s.get("code"))).findFirst().orElse(Map.of());
        ctx.append("DESIGN INTENT (").append(specCode).append("): ")
           .append(spec.getOrDefault("description", "unknown flow")).append('\n')
           .append("Tasks owed: ").append(spec.getOrDefault("taskFlowSpecification", "?"))
           .append('\n');
        ctx.append("FAILED STEP: ").append(taskCode).append(" — ")
           .append(event.getOrDefault("message", "no message")).append('\n');
        Map<String, Object> flow = bss.processFlow(flowId);
        ctx.append("TASK STATES: ").append(flow.getOrDefault("taskFlow", "?")).append('\n');
        ctx.append("CROSS-SYSTEM TIMELINE: ").append(flow.getOrDefault("timeline", "empty"))
           .append('\n');
        return ctx.toString();
    }

    /* ---------- reads + the mandatory verdict ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return traces.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    /** Every trace demands a human verdict — the loop's raw material. */
    @Transactional
    public Map<String, Object> verdict(String traceId, Map<String, Object> dto) {
        IncidentTrace trace = traces.findByIdAndTenantId(traceId, tenantScope.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "no such trace"));
        if (!(dto.get("useful") instanceof Boolean useful)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "useful (true/false) is required — the verdict is mandatory, not optional");
        }
        trace.setVerdict(useful ? "useful" : "not-useful");
        trace.setVerdictNote(dto.get("note") == null ? null : clip(String.valueOf(dto.get("note")), 500));
        traces.save(trace);
        if (useful) {
            maybePromote(trace.getSignature()); // the loop closes on evidence, not enthusiasm
        }
        return view(trace);
    }

    /* ---------- the compounding loop ---------- */

    /** N useful verdicts on one signature draft a candidate runbook —
     * promotion is EARNED, never automatic past the human gate. */
    private void maybePromote(String signature) {
        String tenant = tenantScope.currentTenantId();
        List<IncidentTrace> history =
                traces.findByTenantIdAndSignatureOrderByCreatedAtDesc(tenant, signature);
        long useful = history.stream().filter(t -> "useful".equals(t.getVerdict())).count();
        if (useful < PROMOTION_THRESHOLD || runbooks.existsByTenantIdAndSignatureAndStatusIn(
                tenant, signature, List.of(IncidentRunbook.PROPOSED, IncidentRunbook.APPROVED))) {
            return;
        }
        List<IncidentTrace> evidence = history.stream()
                .filter(t -> "useful".equals(t.getVerdict())).limit(5).toList();
        String draft;
        try {
            draft = governor.complete("incident-runbook-draft", LlmAdapter.Tier.SMART,
                    "You are a runbook author for a telecom BSS operations team. From the"
                    + " consistent, human-confirmed diagnoses below, write ONE reusable"
                    + " runbook. Answer EXACTLY:\nDIAGNOSIS: <the recurring cause, reusable"
                    + " wording, no order-specific ids>\nACTION: <the reusable fix a human"
                    + " operator executes>",
                    "Signature: " + signature + "\nConfirmed diagnoses:\n" + evidence.stream()
                            .map(t -> "- " + t.getHypothesis() + " => " + t.getProposedAction())
                            .reduce("", (a, b) -> a + b + "\n"));
        } catch (Exception e) {
            log.warn("runbook draft failed for {}: {}", signature, e.getMessage());
            return;
        }
        int version = runbooks.findByTenantIdAndSignatureOrderByVersionDesc(tenant, signature)
                .stream().findFirst().map(r -> r.getVersion() + 1).orElse(1);
        IncidentRunbook rb = new IncidentRunbook();
        rb.setId(UUID.randomUUID().toString());
        rb.setTenantId(tenant);
        rb.setSignature(signature);
        rb.setVersion(version);
        rb.setStatus(IncidentRunbook.PROPOSED);
        rb.setTitle("Runbook: " + signature + " v" + version);
        rb.setDiagnosis(clip(section(draft, "DIAGNOSIS") == null
                ? evidence.get(0).getHypothesis() : section(draft, "DIAGNOSIS"), 1900));
        rb.setAction(clip(section(draft, "ACTION") == null
                ? evidence.get(0).getProposedAction() : section(draft, "ACTION"), 900));
        rb.setProvenanceJson(evidence.stream().map(IncidentTrace::getId)
                .reduce("[", (a, b) -> a.equals("[") ? a + "\"" + b + "\"" : a + ",\"" + b + "\"") + "]");
        rb.setCreatedAt(OffsetDateTime.now());
        runbooks.save(rb);
        log.info("runbook DRAFTED for {} (v{}) from {} useful traces — awaiting human decision",
                signature, version, useful);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRunbooks() {
        return runbooks.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::runbookView).toList();
    }

    /** approve | reject | revoke — a decision with a name on it. */
    @Transactional
    public Map<String, Object> decideRunbook(String id, String decision, String note) {
        IncidentRunbook rb = runbooks.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "no such runbook"));
        switch (decision) {
            case "approve" -> {
                if (!IncidentRunbook.PROPOSED.equals(rb.getStatus())) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            "only a proposed runbook can be approved (status: " + rb.getStatus() + ")");
                }
                rb.setStatus(IncidentRunbook.APPROVED);
            }
            case "reject" -> rb.setStatus(IncidentRunbook.REJECTED);
            case "revoke" -> rb.setStatus(IncidentRunbook.REVOKED);
            default -> throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "decision must be approve, reject or revoke");
        }
        rb.setDecidedAt(OffsetDateTime.now());
        rb.setDecidedNote(clip(note, 500));
        runbooks.save(rb);
        return runbookView(rb);
    }

    /** The learning curve as DATA — the number a stateless agent cannot fake. */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<IncidentTrace> all =
                traces.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        long fromRunbook = all.stream().filter(t -> "runbook".equals(t.getSource())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traces", all.size());
        out.put("fromLlm", all.size() - fromRunbook);
        out.put("fromRunbook", fromRunbook);
        out.put("autoDiagnosedRate", all.isEmpty() ? 0
                : Math.round(fromRunbook * 1000.0 / all.size()) / 10.0);
        out.put("verdicts", Map.of(
                "useful", all.stream().filter(t -> "useful".equals(t.getVerdict())).count(),
                "notUseful", all.stream().filter(t -> "not-useful".equals(t.getVerdict())).count(),
                "pending", all.stream().filter(t -> "pending".equals(t.getVerdict())).count()));
        out.put("@type", "IncidentStats");
        return out;
    }

    private Map<String, Object> runbookView(IncidentRunbook rb) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rb.getId());
        map.put("signature", rb.getSignature());
        map.put("version", rb.getVersion());
        map.put("status", rb.getStatus());
        map.put("title", rb.getTitle());
        map.put("diagnosis", rb.getDiagnosis());
        map.put("action", rb.getAction());
        map.put("provenance", rb.getProvenanceJson());
        map.put("createdAt", rb.getCreatedAt());
        if (rb.getDecidedAt() != null) {
            map.put("decidedAt", rb.getDecidedAt());
            map.put("decidedNote", rb.getDecidedNote());
        }
        map.put("@type", "IncidentRunbook");
        return map;
    }

    private Map<String, Object> view(IncidentTrace t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("signature", t.getSignature());
        map.put("processFlowId", t.getProcessFlowId());
        map.put("productOrderId", t.getProductOrderId());
        map.put("hypothesis", t.getHypothesis());
        map.put("confidence", t.getConfidence());
        if (t.getProposedAction() != null) {
            map.put("proposedAction", t.getProposedAction());
        }
        map.put("source", t.getSource());
        if (t.getTicketId() != null) {
            map.put("ticketId", t.getTicketId());
        }
        map.put("verdict", t.getVerdict());
        if (t.getVerdictNote() != null) {
            map.put("verdictNote", t.getVerdictNote());
        }
        map.put("diagnoseMs", t.getDiagnoseMs());
        map.put("createdAt", t.getCreatedAt());
        map.put("@type", "IncidentTrace");
        return map;
    }

    /* ---------- parsing ---------- */

    static String section(String answer, String label) {
        for (String line : answer.split("\n")) {
            if (line.trim().toUpperCase().startsWith(label + ":")) {
                return line.trim().substring(label.length() + 1).trim();
            }
        }
        return null;
    }

    static BigDecimal confidenceOf(String answer) {
        try {
            String c = section(answer, "CONFIDENCE");
            BigDecimal v = new BigDecimal(c.replaceAll("[^0-9.]", ""));
            return v.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : v;
        } catch (Exception e) {
            return new BigDecimal("0.5");
        }
    }

    private static String partyOf(Map<String, Object> event) {
        if (event.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> ref && ref.get("id") != null) {
            return String.valueOf(ref.get("id"));
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
