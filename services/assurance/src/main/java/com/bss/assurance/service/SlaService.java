package com.bss.assurance.service;

import com.bss.assurance.client.AgreementClient;
import com.bss.assurance.entity.ServiceProblem;
import com.bss.assurance.entity.SlaViolation;
import com.bss.assurance.events.DomainEventPublisher;
import com.bss.assurance.repository.SlaViolationRepository;
import com.bss.assurance.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF623, the house way: SLA TERMS ARE AGREEMENT DATA (a `sla` block on
 * the agreement's characteristic — the circuit it covers, the promised
 * resolution time, the pre-agreed credit, the monthly cap). When a
 * problem on that circuit resolves LATE, the violation is minted here —
 * on the ledger the cap is enforced against — and evented; billing
 * compensates with the credit the contract already authorized. Nobody
 * decides anything at breach time: the deciding happened when the
 * agreement was signed.
 */
@Service
public class SlaService {

    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    private final SlaViolationRepository violations;
    private final AgreementClient agreements;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SlaService(SlaViolationRepository violations, AgreementClient agreements,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.violations = violations;
        this.agreements = agreements;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    /** Called on every problem resolution: did any signed promise break? */
    @Transactional
    public void onProblemResolved(ServiceProblem problem) {
        if (problem.getResolvedAt() == null || problem.getCreatedAt() == null) {
            return;
        }
        String tenant = tenantScope.currentTenantId();
        long durationMinutes = Duration.between(problem.getCreatedAt(),
                problem.getResolvedAt()).toMinutes();
        List<Map<String, Object>> active = agreements.activeAgreements();
        log.info("SLA check: problem {} on {} ran {}m; {} agreements to check",
                problem.getId(), problem.getAffectedObject(), durationMinutes, active.size());
        for (Map<String, Object> agreement : active) {
            if (!"active".equals(agreement.get("status"))) {
                continue;
            }
            Map<String, Object> sla = slaOf(agreement);
            if (sla == null
                    || !String.valueOf(problem.getAffectedObject())
                            .equals(sla.get("affectedObject"))) {
                continue;
            }
            long threshold = longOf(sla.get("thresholdMinutes"), Long.MAX_VALUE);
            if (durationMinutes <= threshold) {
                continue; // the promise held
            }
            String agreementId = String.valueOf(agreement.get("id"));
            if (violations.existsByTenantIdAndAgreementIdAndProblemId(
                    tenant, agreementId, problem.getId())) {
                continue; // at-least-once safety
            }
            BigDecimal credit = new BigDecimal(String.valueOf(sla.getOrDefault("creditAmount", "0")));
            BigDecimal cap = new BigDecimal(String.valueOf(sla.getOrDefault("capPerMonth", "0")));
            OffsetDateTime monthStart = OffsetDateTime.now()
                    .with(TemporalAdjusters.firstDayOfMonth()).withHour(0).withMinute(0).withSecond(0);
            BigDecimal creditedThisMonth = violations
                    .findByTenantIdAndAgreementIdAndCreatedAtAfter(tenant, agreementId, monthStart)
                    .stream().filter(SlaViolation::isCredited)
                    .map(v -> v.getCreditAmount() == null ? BigDecimal.ZERO : v.getCreditAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean underCap = cap.signum() <= 0
                    || creditedThisMonth.add(credit).compareTo(cap) <= 0;

            SlaViolation v = new SlaViolation();
            v.setId(UUID.randomUUID().toString());
            v.setTenantId(tenant);
            v.setAgreementId(agreementId);
            v.setPartyId(partyOf(agreement));
            v.setProblemId(problem.getId());
            v.setAffectedObject(problem.getAffectedObject());
            v.setThresholdMinutes(threshold);
            v.setDurationMinutes(durationMinutes);
            v.setCreditAmount(underCap ? credit : BigDecimal.ZERO);
            v.setCredited(underCap);
            v.setNote(underCap
                    ? "resolution " + durationMinutes + "m exceeded the promised " + threshold + "m"
                    : "breach recorded; monthly credit cap " + cap + " already reached — no credit");
            v.setCreatedAt(OffsetDateTime.now());
            violations.save(v);
            events.publish("SlaViolationEvent", "slaViolation", view(v));
            log.info("SLA violation: agreement {} problem {} ({}m > {}m) credit={} capOk={}",
                    agreementId, problem.getId(), durationMinutes, threshold, credit, underCap);
        }
    }

    /* ---------- the TMF623 read faces ---------- */

    /** The SLAs in force: projected live from the agreements that carry terms. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSlas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> agreement : agreements.activeAgreements()) {
            Map<String, Object> sla = slaOf(agreement);
            if (sla == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agreement.get("id"));
            row.put("name", "SLA — " + agreement.get("name"));
            row.put("state", agreement.get("status"));
            row.put("relatedParty", agreement.get("engagedParty"));
            row.put("template", sla);
            row.put("@type", "SLA");
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listViolations() {
        return violations.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    /* ---------- internals ---------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> slaOf(Map<String, Object> agreement) {
        Object chars = agreement.get("characteristic");
        if (chars instanceof Map<?, ?> m && m.get("sla") instanceof Map<?, ?> sla) {
            return (Map<String, Object>) sla;
        }
        if (chars instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> cm && "sla".equals(cm.get("name"))
                        && cm.get("value") instanceof Map<?, ?> sla) {
                    return (Map<String, Object>) sla;
                }
            }
        }
        return null;
    }

    private static String partyOf(Map<String, Object> agreement) {
        if (agreement.get("engagedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> ref && ref.get("id") != null) {
            return String.valueOf(ref.get("id"));
        }
        return null;
    }

    private static long longOf(Object v, long dflt) {
        try {
            return v == null ? dflt : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private Map<String, Object> view(SlaViolation v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", v.getId());
        map.put("agreementId", v.getAgreementId());
        map.put("problemId", v.getProblemId());
        map.put("affectedObject", v.getAffectedObject());
        map.put("thresholdMinutes", v.getThresholdMinutes());
        map.put("durationMinutes", v.getDurationMinutes());
        map.put("creditAmount", v.getCreditAmount());
        map.put("credited", v.isCredited());
        map.put("note", v.getNote());
        if (v.getPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", v.getPartyId(), "role", "customer")));
        }
        map.put("createdAt", v.getCreatedAt());
        map.put("@type", "SlaViolation");
        return map;
    }
}
