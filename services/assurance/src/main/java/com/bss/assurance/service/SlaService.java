package com.bss.assurance.service;

import com.bss.assurance.client.AgreementClient;
import com.bss.assurance.dto.CustomerRef;
import com.bss.assurance.dto.Json;
import com.bss.assurance.dto.SlaView;
import com.bss.assurance.dto.SlaViolationView;
import com.bss.assurance.entity.ServiceProblem;
import com.bss.assurance.entity.SlaViolation;
import com.bss.assurance.events.DomainEventPublisher;
import com.bss.assurance.repository.SlaViolationRepository;
import com.bss.assurance.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
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
        List<JsonNode> active = agreements.activeAgreements();
        log.info("SLA check: problem {} on {} ran {}m; {} agreements to check",
                problem.getId(), problem.getAffectedObject(), durationMinutes, active.size());
        for (JsonNode agreement : active) {
            if (!isText(agreement.get("status"), "active")) {
                continue;
            }
            JsonNode sla = slaOf(agreement);
            if (sla == null
                    || !isText(sla.get("affectedObject"),
                            String.valueOf(problem.getAffectedObject()))) {
                continue;
            }
            long threshold = longOf(sla.get("thresholdMinutes"), Long.MAX_VALUE);
            if (durationMinutes <= threshold) {
                continue; // the promise held
            }
            String agreementId = Json.valueOf(agreement.get("id"));
            if (violations.existsByTenantIdAndAgreementIdAndProblemId(
                    tenant, agreementId, problem.getId())) {
                continue; // at-least-once safety
            }
            BigDecimal credit = new BigDecimal(orDefault(sla, "creditAmount"));
            BigDecimal cap = new BigDecimal(orDefault(sla, "capPerMonth"));
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
    public List<SlaView> listSlas() {
        List<SlaView> out = new ArrayList<>();
        for (JsonNode agreement : agreements.activeAgreements()) {
            JsonNode sla = slaOf(agreement);
            if (sla == null) {
                continue;
            }
            out.add(new SlaView(agreement.get("id"), "SLA — " + Json.valueOf(agreement.get("name")),
                    agreement.get("status"), agreement.get("engagedParty"), sla));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<SlaViolationView> listViolations() {
        return violations.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    /* ---------- internals ---------- */

    /** The terms, wherever the agreement carries them: a characteristic map, or a named entry. */
    private JsonNode slaOf(JsonNode agreement) {
        JsonNode chars = agreement.get("characteristic");
        if (chars == null) {
            return null;
        }
        if (chars.isObject() && chars.path("sla").isObject()) {
            return chars.get("sla");
        }
        if (chars.isArray()) {
            for (JsonNode c : chars) {
                if (c.isObject() && isText(c.get("name"), "sla") && c.path("value").isObject()) {
                    return c.get("value");
                }
            }
        }
        return null;
    }

    private static String partyOf(JsonNode agreement) {
        JsonNode parties = agreement.get("engagedParty");
        if (parties != null && parties.isArray() && !parties.isEmpty()
                && parties.get(0).isObject() && parties.get(0).hasNonNull("id")) {
            return Json.valueOf(parties.get(0).get("id"));
        }
        return null;
    }

    /** The map compared with a String, so only a JSON string ever matched. */
    private static boolean isText(JsonNode node, String expected) {
        return node != null && node.isTextual() && expected.equals(node.textValue());
    }

    /** {@code getOrDefault(k, "0")}: a key present with a JSON null wins the null. */
    private static String orDefault(JsonNode sla, String key) {
        return sla.has(key) ? Json.valueOf(sla.get(key)) : "0";
    }

    private static long longOf(JsonNode v, long dflt) {
        try {
            return v == null ? dflt : Long.parseLong(Json.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private SlaViolationView view(SlaViolation v) {
        return new SlaViolationView(v.getId(), v.getAgreementId(), v.getProblemId(),
                v.getAffectedObject(), v.getThresholdMinutes(), v.getDurationMinutes(),
                v.getCreditAmount(), v.isCredited(), v.getNote(),
                v.getPartyId() == null ? null : List.of(CustomerRef.customer(v.getPartyId())),
                v.getCreatedAt());
    }
}
