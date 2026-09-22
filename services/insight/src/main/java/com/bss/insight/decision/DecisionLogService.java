package com.bss.insight.decision;

import com.bss.insight.dto.DecisionInput;
import com.bss.insight.dto.DecisionReceipt;
import com.bss.insight.dto.DecisionSummary;
import com.bss.insight.dto.DecisionView;
import com.bss.insight.entity.DecisionLog;
import com.bss.insight.repository.DecisionLogRepository;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

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
    public boolean record(String tenantId, DecisionInput decision) {
        String id = decision.decisionId();
        if (id == null || id.isBlank() || id.length() > 64) {
            log.warn("decision without a usable id skipped: {}", decision.decisionPoint());
            return false;
        }
        if (decisions.existsById(id)) {
            return false;
        }
        DecisionLog d = new DecisionLog();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setDecisionPoint(cut(decision.decisionPoint(), 64));
        d.setSubjectType(cut(decision.subjectType(), 32));
        d.setSubjectId(cut(decision.subjectId(), 64));
        d.setCandidates(encode(decision.candidates()));
        d.setEligible(encode(decision.eligibleActions()));
        d.setConstraints(encode(decision.constraints()));
        d.setAction(cut(decision.action(), 120));
        d.setPropensity(decision.propensity() == null ? null : BigDecimal.valueOf(decision.propensity()));
        d.setPolicy(cut(decision.policy() == null ? "unknown" : decision.policy(), 64));
        d.setPolicyVersion(cut(decision.policyVersion() == null ? "0" : decision.policyVersion(), 16));
        d.setReason(cut(decision.reason(), 1000));
        d.setContext(encode(decision.context()));
        d.setEvidence(encode(decision.evidence()));
        d.setAutonomy(cut(decision.autonomy(), 8));
        d.setFallback(Boolean.TRUE.equals(decision.fallback()));
        d.setSource(cut(decision.source() == null ? "unknown" : decision.source(), 40));
        d.setDecidedAt(parseTime(decision.decidedAt()));
        d.setContract(cut(decision.contract(), 80));
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
    public List<DecisionView> list(String decisionPoint, String subjectId, int limit) {
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
    public DecisionReceipt receipt(String id) {
        DecisionLog d = decisions.findByIdAndTenantId(id, tenantScope.currentTenantId()).orElse(null);
        if (d == null) {
            return null;
        }
        DecisionView view = view(d);
        List<String> lines = new ArrayList<>();
        List<String> ctxKeys = new ArrayList<>();
        view.context().fieldNames().forEachRemaining(ctxKeys::add);
        lines.add("Context used: " + (ctxKeys.isEmpty() ? "none" : String.join(", ", ctxKeys)) + ".");
        JsonNode candidates = view.candidates();
        JsonNode eligible = view.eligibleActions();
        JsonNode constraints = view.constraints();
        lines.add("Eligible actions: " + join(eligible) + (candidates.size() == eligible.size() ? ""
                : " (of " + candidates.size() + " candidates; removed by constraints: " + join(constraints) + ")") + ".");
        lines.add("Chosen: \"" + d.getAction() + "\" by policy " + d.getPolicy() + " v" + d.getPolicyVersion()
                + (d.getPropensity() == null ? " (deterministic)"
                        : " with probability " + d.getPropensity().stripTrailingZeros().toPlainString())
                + (d.isFallback() ? " — FALLBACK, the policy did not answer" : "") + ".");
        lines.add("Why: " + (d.getReason() == null ? "no reason recorded" : d.getReason()));
        lines.add("Autonomy: " + (d.getAutonomy() == null ? "unclassified" : d.getAutonomy())
                + " — decided by " + d.getSource() + " at " + d.getDecidedAt()
                + (d.getContract() == null ? ", no learning contract (defaults)." : ", under learning contract " + d.getContract() + "."));
        lines.add(d.getOutcome() == null ? "Outcome: none attributed yet."
                : "Outcome: " + d.getOutcome() + (d.getOutcomeValue() == null ? ""
                        : " worth " + d.getOutcomeValue().stripTrailingZeros().toPlainString()) + " at " + d.getOutcomeAt() + ".");
        return new DecisionReceipt(view.asReceipt(), lines);
    }

    @Transactional(readOnly = true)
    public DecisionSummary summary() {
        List<DecisionSummary.Point> points = new ArrayList<>();
        long total = 0, withOutcome = 0, withPropensity = 0;
        for (Object[] r : decisions.summary(tenantScope.currentTenantId())) {
            long n = ((Number) r[5]).longValue();
            long p = r[6] == null ? 0 : ((Number) r[6]).longValue();
            long o = r[7] == null ? 0 : ((Number) r[7]).longValue();
            long f = r[8] == null ? 0 : ((Number) r[8]).longValue();
            points.add(new DecisionSummary.Point((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    n, p, o, f, n == 0 ? 0 : Math.round(1000.0 * o / n) / 10.0, (OffsetDateTime) r[9]));
            total += n;
            withOutcome += o;
            withPropensity += p;
        }
        return new DecisionSummary(total, withOutcome, withPropensity, points, "DecisionLogSummary");
    }

    /* ------------------------------------------------------------------ helpers */

    private DecisionView view(DecisionLog d) {
        DecisionView.Outcome outcome = d.getOutcome() == null ? null
                : new DecisionView.Outcome(d.getOutcome(), d.getOutcomeValue(), d.getOutcomeAt());
        return new DecisionView(d.getId(), d.getDecisionPoint(), d.getSubjectType(), d.getSubjectId(),
                decodeList(d.getCandidates()), decodeList(d.getEligible()), decodeList(d.getConstraints()), d.getAction(),
                d.getPropensity(), d.getPolicy(), d.getPolicyVersion(), d.getReason(), decodeMap(d.getContext()),
                decodeMap(d.getEvidence()), d.getAutonomy(), d.isFallback(), d.getSource(), d.getDecidedAt(),
                d.getContract(), outcome, "Decision");
    }

    private String encode(JsonNode o) {
        if (o == null || o.isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored object block, or an empty object when there is none or it does not parse as one. */
    private JsonNode decodeMap(String s) {
        if (s == null || s.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode n = json.readTree(s);
            return n.isObject() ? n : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** The stored list block, or an empty list when there is none or it does not parse as one. */
    private JsonNode decodeList(String s) {
        if (s == null || s.isBlank()) {
            return json.createArrayNode();
        }
        try {
            JsonNode n = json.readTree(s);
            return n.isArray() ? n : json.createArrayNode();
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }

    private static String join(JsonNode l) {
        if (l.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode n : l) {
            parts.add(n.isTextual() ? n.asText() : n.toString());
        }
        return String.join(", ", parts);
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

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
