package com.bss.campaign.service;

import com.bss.campaign.decision.Contract;
import com.bss.campaign.decision.ContractProvider;
import com.bss.campaign.decision.DecisionPoints;
import com.bss.campaign.decision.DecisionRecord;
import com.bss.campaign.entity.LearningContract;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.repository.LearningContractRepository;
import com.bss.campaign.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * LEARNING CONTRACTS: intent as configuration, one per DecisionPoint per
 * tenant, edited on the same desk as launch envelopes. The seam reads the
 * contract on every decision (allowed actions, exploration cap, autonomy,
 * fallback, pause) and every record cites the contract version it ran under,
 * so a change of intent is as attributable as a change of policy.
 */
@Service
public class LearningContractService implements ContractProvider {

    public static final Set<String> AUTONOMY = Set.of("high", "medium", "low");
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final LearningContractRepository contracts;
    private final TenantScope tenantScope;
    private final ObjectMapper json;
    private final DecisionPoints decisions;

    public LearningContractService(LearningContractRepository contracts, TenantScope tenantScope, ObjectMapper json,
            @Lazy DecisionPoints decisions) {
        this.contracts = contracts;
        this.tenantScope = tenantScope;
        this.json = json;
        this.decisions = decisions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> contractFor(String decisionPoint) {
        return contracts.findByTenantIdAndDecisionPoint(tenantScope.currentTenantId(), decisionPoint)
                .map(this::toContract);
    }

    /** Every registered point with its contract — or the defaults, marked as such. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> effective() {
        String tenant = tenantScope.currentTenantId();
        Map<String, LearningContract> byPoint = new LinkedHashMap<>();
        for (LearningContract c : contracts.findByTenantIdOrderByDecisionPoint(tenant)) {
            byPoint.put(c.getDecisionPoint(), c);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> point : decisions.registryView()) {
            String name = String.valueOf(point.get("name"));
            Map<String, Object> m = new LinkedHashMap<>(point);
            LearningContract c = byPoint.get(name);
            m.put("contract", c == null ? defaults(name) : view(c));
            m.put("@type", "LearningContract");
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String decisionPoint) {
        try {
            decisions.spec(decisionPoint);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        Map<String, Object> point = decisions.registryView().stream()
                .filter(p -> decisionPoint.equals(p.get("name"))).findFirst().orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>(point);
        m.put("contract", contracts.findByTenantIdAndDecisionPoint(tenantScope.currentTenantId(), decisionPoint)
                .map(this::view).orElse(defaults(decisionPoint)));
        m.put("@type", "LearningContract");
        return m;
    }

    /** Upsert: a new version each time; the previous intent stays attributable through old records' "id@version". */
    @Transactional
    public Map<String, Object> put(String decisionPoint, Map<String, Object> dto) {
        try {
            decisions.spec(decisionPoint);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        String tenant = tenantScope.currentTenantId();
        LearningContract c = contracts.findByTenantIdAndDecisionPoint(tenant, decisionPoint).orElse(null);
        if (c == null) {
            c = new LearningContract();
            c.setId(UUID.randomUUID().toString());
            c.setTenantId(tenant);
            c.setDecisionPoint(decisionPoint);
            c.setVersion(0);
        }
        String autonomy = str(dto.get("autonomy"));
        if (autonomy != null && !AUTONOMY.contains(autonomy)) {
            throw new BadRequestException("autonomy must be high, medium or low");
        }
        Integer cap = null;
        if (dto.get("explorationMaxPercent") != null && !String.valueOf(dto.get("explorationMaxPercent")).isBlank()) {
            try {
                cap = Integer.valueOf(String.valueOf(dto.get("explorationMaxPercent")).trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("explorationMaxPercent must be a whole number 0–90");
            }
            if (cap < 0 || cap > 90) {
                throw new BadRequestException("explorationMaxPercent must be 0–90");
            }
        }
        c.setObjective(str(dto.get("objective")));
        c.setSecondaryMetrics(encodeList(dto.get("secondaryMetrics")));
        c.setGuardrails(encodeList(dto.get("guardrails")));
        c.setAllowedActions(dto.get("allowedActions") == null ? null : encodeList(dto.get("allowedActions")));
        c.setExplorationMaxPercent(cap);
        c.setAutonomy(autonomy);
        c.setFallbackAction(str(dto.get("fallbackAction")));
        c.setEnabled(dto.get("enabled") == null || Boolean.parseBoolean(String.valueOf(dto.get("enabled"))));
        c.setNotes(str(dto.get("notes")));
        c.setVersion(c.getVersion() + 1);
        c.setUpdatedBy(caller());
        c.setLastUpdate(OffsetDateTime.now());
        contracts.save(c);
        return get(decisionPoint);
    }

    @Transactional
    public void delete(String decisionPoint) {
        contracts.findByTenantIdAndDecisionPoint(tenantScope.currentTenantId(), decisionPoint)
                .ifPresent(contracts::delete);
    }

    /** Dry run: what the point WOULD decide for a sample context under the current contract — nothing recorded. */
    @Transactional(readOnly = true)
    public Map<String, Object> dryRun(String decisionPoint, Map<String, Object> body) {
        try {
            decisions.spec(decisionPoint);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
        List<String> candidates = body.get("candidates") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        if (candidates.isEmpty()) {
            throw new BadRequestException("candidates is required — the actions the point could take");
        }
        String subject = str(body.get("subjectId"));
        if (subject == null) {
            subject = str(context.get("partyId"));
        }
        if (context.get("seed") == null) {
            context.put("seed", str(context.getOrDefault("journeyId", context.getOrDefault("campaignId", "dry-run"))));
        }
        DecisionRecord r = decisions.preview(decisionPoint, subject == null ? "dry-run" : subject, context, candidates,
                str(body.getOrDefault("fallbackAction", candidates.get(0))));
        Map<String, Object> out = r.toMap();
        out.remove("decisionId"); // nothing was recorded
        out.put("dryRun", true);
        out.put("@type", "DecisionDryRun");
        return out;
    }

    /* ------------------------------------------------------------------ helpers */

    private Contract toContract(LearningContract c) {
        return new Contract(c.getId(), c.getVersion(), c.getObjective(), decodeList(c.getSecondaryMetrics()),
                decodeList(c.getGuardrails()), c.getAllowedActions() == null ? null : decodeList(c.getAllowedActions()),
                c.getExplorationMaxPercent(), c.getAutonomy(), c.getFallbackAction(), c.isEnabled());
    }

    private Map<String, Object> view(LearningContract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("version", c.getVersion());
        m.put("ref", c.getId() + "@" + c.getVersion());
        m.put("objective", c.getObjective());
        m.put("secondaryMetrics", decodeList(c.getSecondaryMetrics()));
        m.put("guardrails", decodeList(c.getGuardrails()));
        m.put("allowedActions", c.getAllowedActions() == null ? null : decodeList(c.getAllowedActions()));
        m.put("explorationMaxPercent", c.getExplorationMaxPercent());
        m.put("autonomy", c.getAutonomy());
        m.put("fallbackAction", c.getFallbackAction());
        m.put("enabled", c.isEnabled());
        m.put("notes", c.getNotes());
        m.put("updatedBy", c.getUpdatedBy());
        m.put("lastUpdate", c.getLastUpdate());
        m.put("defaults", false);
        return m;
    }

    private Map<String, Object> defaults(String point) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objective", DecisionPoints.JOURNEY_ENROLMENT.equals(point) || DecisionPoints.CAMPAIGN_TREATMENT.equals(point)
                ? "conversion" : null);
        m.put("secondaryMetrics", List.of());
        m.put("guardrails", List.of());
        m.put("allowedActions", null);
        m.put("explorationMaxPercent", null);
        m.put("autonomy", null);
        m.put("fallbackAction", null);
        m.put("enabled", true);
        m.put("version", 0);
        m.put("defaults", true);
        return m;
    }

    private String encodeList(Object o) {
        List<String> list = o instanceof List<?> l ? l.stream().map(String::valueOf).map(String::trim)
                .filter(s -> !s.isEmpty()).toList()
                : o instanceof String s && !s.isBlank() ? List.of(s.split("\\s*[\\n,]\\s*")) : List.of();
        try {
            return json.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> decodeList(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return new LinkedHashMap<>((Map<String, Object>) m);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String caller() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? null : auth.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
