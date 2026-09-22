package com.bss.campaign.service;

import com.bss.campaign.decision.Contract;
import com.bss.campaign.decision.ContractProvider;
import com.bss.campaign.decision.DecisionPoints;
import com.bss.campaign.decision.DecisionRecord;
import com.bss.campaign.dto.ContractView;
import com.bss.campaign.dto.DecisionDryRun;
import com.bss.campaign.dto.DecisionPointView;
import com.bss.campaign.dto.DryRunRequest;
import com.bss.campaign.dto.LearningContractRequest;
import com.bss.campaign.dto.LearningContractView;
import com.bss.campaign.entity.LearningContract;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.repository.LearningContractRepository;
import com.bss.campaign.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

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
    public List<LearningContractView> effective() {
        String tenant = tenantScope.currentTenantId();
        Map<String, LearningContract> byPoint = new LinkedHashMap<>();
        for (LearningContract c : contracts.findByTenantIdOrderByDecisionPoint(tenant)) {
            byPoint.put(c.getDecisionPoint(), c);
        }
        List<LearningContractView> out = new ArrayList<>();
        for (DecisionPointView point : decisions.registryView()) {
            LearningContract c = byPoint.get(point.name());
            out.add(LearningContractView.of(point, c == null ? defaults(point.name()) : view(c)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public LearningContractView get(String decisionPoint) {
        requireKnown(decisionPoint);
        DecisionPointView point = decisions.registryView().stream()
                .filter(p -> decisionPoint.equals(p.name())).findFirst().orElseThrow();
        return LearningContractView.of(point,
                contracts.findByTenantIdAndDecisionPoint(tenantScope.currentTenantId(), decisionPoint)
                        .map(this::view).orElse(defaults(decisionPoint)));
    }

    /** Upsert: a new version each time; the previous intent stays attributable through old records' "id@version". */
    @Transactional
    public LearningContractView put(String decisionPoint, LearningContractRequest dto) {
        requireKnown(decisionPoint);
        String tenant = tenantScope.currentTenantId();
        LearningContract c = contracts.findByTenantIdAndDecisionPoint(tenant, decisionPoint).orElse(null);
        if (c == null) {
            c = new LearningContract();
            c.setId(UUID.randomUUID().toString());
            c.setTenantId(tenant);
            c.setDecisionPoint(decisionPoint);
            c.setVersion(0);
        }
        String autonomy = str(dto.autonomy());
        if (autonomy != null && !AUTONOMY.contains(autonomy)) {
            throw new BadRequestException("autonomy must be high, medium or low");
        }
        Integer cap = null;
        String capText = text(dto.explorationMaxPercent());
        if (capText != null && !capText.isBlank()) {
            try {
                cap = Integer.valueOf(capText.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("explorationMaxPercent must be a whole number 0–90");
            }
            if (cap < 0 || cap > 90) {
                throw new BadRequestException("explorationMaxPercent must be 0–90");
            }
        }
        c.setObjective(str(dto.objective()));
        c.setSecondaryMetrics(encodeList(dto.secondaryMetrics()));
        c.setGuardrails(encodeList(dto.guardrails()));
        c.setAllowedActions(absent(dto.allowedActions()) ? null : encodeList(dto.allowedActions()));
        c.setExplorationMaxPercent(cap);
        c.setAutonomy(autonomy);
        c.setFallbackAction(str(dto.fallbackAction()));
        c.setEnabled(absent(dto.enabled()) || Boolean.parseBoolean(dto.enabled().asText()));
        c.setNotes(str(dto.notes()));
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
    public DecisionDryRun dryRun(String decisionPoint, DryRunRequest body) {
        requireKnown(decisionPoint);
        Map<String, Object> context = body.context() != null && body.context().isObject()
                ? json.convertValue(body.context(), OBJECT) : new LinkedHashMap<>();
        List<String> candidates = body.candidates() == null ? List.of() : body.candidates();
        if (candidates.isEmpty()) {
            throw new BadRequestException("candidates is required — the actions the point could take");
        }
        String subject = str(body.subjectId());
        if (subject == null) {
            subject = str(context.get("partyId"));
        }
        if (context.get("seed") == null) {
            context.put("seed", str(context.getOrDefault("journeyId", context.getOrDefault("campaignId", "dry-run"))));
        }
        String fallback = body.fallbackAction() == null ? candidates.get(0) : str(body.fallbackAction());
        DecisionRecord r = decisions.preview(decisionPoint, subject == null ? "dry-run" : subject, context, candidates,
                fallback);
        return DecisionDryRun.of(r.view());
    }

    /* ------------------------------------------------------------------ helpers */

    private Contract toContract(LearningContract c) {
        return new Contract(c.getId(), c.getVersion(), c.getObjective(), decodeList(c.getSecondaryMetrics()),
                decodeList(c.getGuardrails()), c.getAllowedActions() == null ? null : decodeList(c.getAllowedActions()),
                c.getExplorationMaxPercent(), c.getAutonomy(), c.getFallbackAction(), c.isEnabled());
    }

    private ContractView view(LearningContract c) {
        return new ContractView.Stored(c.getId(), c.getVersion(), c.getId() + "@" + c.getVersion(), c.getObjective(),
                decodeList(c.getSecondaryMetrics()), decodeList(c.getGuardrails()),
                c.getAllowedActions() == null ? null : decodeList(c.getAllowedActions()),
                c.getExplorationMaxPercent(), c.getAutonomy(), c.getFallbackAction(), c.isEnabled(), c.getNotes(),
                c.getUpdatedBy(), c.getLastUpdate(), false);
    }

    private ContractView defaults(String point) {
        return ContractView.Defaults.withObjective(
                DecisionPoints.JOURNEY_ENROLMENT.equals(point) || DecisionPoints.CAMPAIGN_TREATMENT.equals(point)
                        ? "conversion" : null);
    }

    /** A list field as the desk sends it: a JSON list, or one string split on commas/newlines. */
    private String encodeList(JsonNode o) {
        List<String> list;
        if (o != null && o.isArray()) {
            List<String> items = new ArrayList<>();
            o.forEach(n -> items.add(text(n)));
            list = items.stream().map(s -> s == null ? "" : s.trim()).filter(s -> !s.isEmpty()).toList();
        } else if (o != null && o.isTextual() && !o.asText().isBlank()) {
            list = List.of(o.asText().split("\\s*[\\n,]\\s*"));
        } else {
            list = List.of();
        }
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

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull();
    }

    private static String text(JsonNode n) {
        if (absent(n)) {
            return null;
        }
        return n.isValueNode() ? n.asText() : n.toString();
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

    /**
     * A decision point must be registered before it can carry a contract. The
     * campaign's own points are born registered; an action of the operational
     * ontology (ontology.&lt;action&gt;) registers itself on first contact, so an
     * operator can write the intent for it — what it is measured by, what must
     * never happen — under the same vocabulary as every other point.
     */
    private void requireKnown(String decisionPoint) {
        try {
            decisions.spec(decisionPoint);
        } catch (IllegalArgumentException unknown) {
            if (decisionPoint != null && decisionPoint.startsWith("ontology.") && decisionPoint.length() > "ontology.".length()) {
                decisions.register(new com.bss.campaign.decision.DecisionPointSpec(decisionPoint, "subscription", "medium",
                        "a governed action of the operational ontology — the registry executes what the caller chose; the contract states what learning may measure and what must never happen",
                        new com.bss.campaign.decision.OntologyActionPolicy()));
                return;
            }
            throw new BadRequestException(unknown.getMessage());
        }
    }
}
