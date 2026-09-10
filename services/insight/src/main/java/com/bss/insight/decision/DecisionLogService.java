package com.bss.insight.decision;

import com.bss.insight.entity.DecisionLog;
import com.bss.insight.repository.DecisionLogRepository;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE DECISION LOG. Every service that makes an adaptive choice publishes a
 * {@code DecisionRecordedEvent}; the outcome that follows arrives as a
 * {@code DecisionOutcomeEvent} and joins by id. Insight keeps the log because
 * it already keeps the customer's traits and the desks' behaviour — the
 * learning evidence lives in one place, behind one RLS policy.
 *
 * <p>What is NOT here: names, addresses, message text. A record carries
 * identifiers, the eligible actions, numbers and one sentence of reason.
 */
@Service
public class DecisionLogService {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() { };

    private final DecisionLogRepository decisions;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    public DecisionLogService(DecisionLogRepository decisions, TenantScope tenantScope, ObjectMapper json) {
        this.decisions = decisions;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    /** Record a decision (idempotent by id — the bus is at-least-once). Returns false when it was already there. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(String tenantId, Map<String, Object> decision) {
        String id = str(decision.get("decisionId"));
        if (id == null || id.isBlank() || id.length() > 64) {
            log.warn("decision without a usable id skipped: {}", decision.get("decisionPoint"));
            return false;
        }
        if (decisions.existsById(id)) {
            return false;
        }
        DecisionLog d = new DecisionLog();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setDecisionPoint(cut(str(decision.get("decisionPoint")), 64));
        d.setSubjectType(cut(str(decision.get("subjectType")), 32));
        d.setSubjectId(cut(str(decision.get("subjectId")), 64));
        d.setCandidates(encode(decision.get("candidates")));
        d.setEligible(encode(decision.get("eligibleActions")));
        d.setConstraints(encode(decision.get("constraints")));
        d.setAction(cut(str(decision.get("action")), 120));
        d.setPropensity(decision.get("propensity") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
        d.setPolicy(cut(str(decision.getOrDefault("policy", "unknown")), 64));
        d.setPolicyVersion(cut(str(decision.getOrDefault("policyVersion", "0")), 16));
        d.setReason(cut(str(decision.get("reason")), 1000));
        d.setContext(encode(decision.get("context")));
        d.setEvidence(encode(decision.get("evidence")));
        d.setAutonomy(cut(str(decision.get("autonomy")), 8));
        d.setFallback(Boolean.TRUE.equals(decision.get("fallback")));
        d.setSource(cut(str(decision.getOrDefault("source", "unknown")), 40));
        d.setDecidedAt(parseTime(decision.get("decidedAt")));
        decisions.save(d);
        return true;
    }

    /** The outcome that followed a decision. Unknown ids are logged and dropped — never invented. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean outcome(String tenantId, String decisionId, String outcome, Object value, Object at) {
        if (decisionId == null) {
            return false;
        }
        DecisionLog d = decisions.findByIdAndTenantId(decisionId, tenantId).orElse(null);
        if (d == null) {
            log.info("outcome '{}' for unknown decision {} — dropped", outcome, decisionId);
            return false;
        }
        d.setOutcome(cut(outcome, 40));
        if (value instanceof Number n) {
            d.setOutcomeValue(BigDecimal.valueOf(n.doubleValue()));
        } else if (value instanceof String s && !s.isBlank()) {
            try { d.setOutcomeValue(new BigDecimal(s)); } catch (NumberFormatException ignore) { /* not a number */ }
        }
        d.setOutcomeAt(at == null ? OffsetDateTime.now() : parseTime(at));
        decisions.save(d);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String decisionPoint, String subjectId, int limit) {
        String tenant = tenantScope.currentTenantId();
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<DecisionLog> rows;
        if (decisionPoint != null && subjectId != null) {
            rows = decisions.findByTenantIdAndDecisionPointAndSubjectIdOrderByDecidedAtDesc(tenant, decisionPoint, subjectId, page);
        } else if (decisionPoint != null) {
            rows = decisions.findByTenantIdAndDecisionPointOrderByDecidedAtDesc(tenant, decisionPoint, page);
        } else if (subjectId != null) {
            rows = decisions.findByTenantIdAndSubjectIdOrderByDecidedAtDesc(tenant, subjectId, page);
        } else {
            rows = decisions.findByTenantIdOrderByDecidedAtDesc(tenant, page);
        }
        return rows.stream().map(this::view).toList();
    }

    /** The receipt: the record plus the sentences an auditor reads first. */
    @Transactional(readOnly = true)
    public Map<String, Object> receipt(String id) {
        DecisionLog d = decisions.findByIdAndTenantId(id, tenantScope.currentTenantId()).orElse(null);
        if (d == null) {
            return null;
        }
        Map<String, Object> out = view(d);
        List<String> lines = new ArrayList<>();
        Map<String, Object> ctx = decodeMap(d.getContext());
        lines.add("Context used: " + (ctx.isEmpty() ? "none" : String.join(", ", ctx.keySet())) + ".");
        List<Object> candidates = decodeList(d.getCandidates());
        List<Object> eligible = decodeList(d.getEligible());
        List<Object> constraints = decodeList(d.getConstraints());
        lines.add("Eligible actions: " + join(eligible) + (candidates.size() == eligible.size() ? ""
                : " (of " + candidates.size() + " candidates; removed by constraints: " + join(constraints) + ")") + ".");
        lines.add("Chosen: \"" + d.getAction() + "\" by policy " + d.getPolicy() + " v" + d.getPolicyVersion()
                + (d.getPropensity() == null ? " (deterministic)"
                        : " with probability " + d.getPropensity().stripTrailingZeros().toPlainString())
                + (d.isFallback() ? " — FALLBACK, the policy did not answer" : "") + ".");
        lines.add("Why: " + (d.getReason() == null ? "no reason recorded" : d.getReason()));
        lines.add("Autonomy: " + (d.getAutonomy() == null ? "unclassified" : d.getAutonomy())
                + " — decided by " + d.getSource() + " at " + d.getDecidedAt() + ".");
        lines.add(d.getOutcome() == null ? "Outcome: none attributed yet."
                : "Outcome: " + d.getOutcome() + (d.getOutcomeValue() == null ? ""
                        : " worth " + d.getOutcomeValue().stripTrailingZeros().toPlainString()) + " at " + d.getOutcomeAt() + ".");
        out.put("receipt", lines);
        out.put("@type", "DecisionReceipt");
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<Map<String, Object>> points = new ArrayList<>();
        long total = 0, withOutcome = 0, withPropensity = 0;
        for (Object[] r : decisions.summary(tenantScope.currentTenantId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decisionPoint", r[0]);
            m.put("policy", r[1]);
            m.put("policyVersion", r[2]);
            m.put("source", r[3]);
            m.put("autonomy", r[4]);
            long n = ((Number) r[5]).longValue();
            long p = r[6] == null ? 0 : ((Number) r[6]).longValue();
            long o = r[7] == null ? 0 : ((Number) r[7]).longValue();
            long f = r[8] == null ? 0 : ((Number) r[8]).longValue();
            m.put("decisions", n);
            m.put("withPropensity", p);
            m.put("withOutcome", o);
            m.put("fallbacks", f);
            m.put("outcomeRate", n == 0 ? 0 : Math.round(1000.0 * o / n) / 10.0);
            m.put("lastDecidedAt", r[9]);
            points.add(m);
            total += n;
            withOutcome += o;
            withPropensity += p;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisions", total);
        out.put("withOutcome", withOutcome);
        out.put("withPropensity", withPropensity);
        out.put("points", points);
        out.put("@type", "DecisionLogSummary");
        return out;
    }

    /* ------------------------------------------------------------------ helpers */

    private Map<String, Object> view(DecisionLog d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", d.getId());
        m.put("decisionPoint", d.getDecisionPoint());
        m.put("subjectType", d.getSubjectType());
        m.put("subjectId", d.getSubjectId());
        m.put("candidates", decodeList(d.getCandidates()));
        m.put("eligibleActions", decodeList(d.getEligible()));
        m.put("constraints", decodeList(d.getConstraints()));
        m.put("action", d.getAction());
        m.put("propensity", d.getPropensity());
        m.put("policy", d.getPolicy());
        m.put("policyVersion", d.getPolicyVersion());
        m.put("reason", d.getReason());
        m.put("context", decodeMap(d.getContext()));
        m.put("evidence", decodeMap(d.getEvidence()));
        m.put("autonomy", d.getAutonomy());
        m.put("fallback", d.isFallback());
        m.put("source", d.getSource());
        m.put("decidedAt", d.getDecidedAt());
        if (d.getOutcome() != null) {
            m.put("outcome", d.getOutcome());
            m.put("outcomeValue", d.getOutcomeValue());
            m.put("outcomeAt", d.getOutcomeAt());
        }
        m.put("@type", "Decision");
        return m;
    }

    private String encode(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> decodeMap(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(s, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Object> decodeList(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String join(List<Object> l) {
        return l.isEmpty() ? "none" : String.join(", ", l.stream().map(String::valueOf).toList());
    }

    private static OffsetDateTime parseTime(Object o) {
        if (o == null) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(String.valueOf(o));
        } catch (Exception e) {
            return OffsetDateTime.now();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
