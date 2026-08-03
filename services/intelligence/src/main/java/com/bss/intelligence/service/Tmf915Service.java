package com.bss.intelligence.service;

import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContract;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.churn.ChurnModelRecord;
import com.bss.intelligence.churn.ChurnModelRepository;
import com.bss.intelligence.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * TMF915, the house way: the AI control plane's standard face. Nothing here
 * is a registry anybody fills in — a MODEL is whatever the audit ledger
 * proves has served (plus the one genuinely versioned trained artifact, the
 * churn model), and a MODEL CONTRACT is a scenario the governor has metered,
 * carrying its real monitoring numbers: calls, tokens, spend, latency, and
 * every refusal class. The face reports what RAN, not what config promises.
 * The one write is the in-life lever TMF915 exists for: suspending a single
 * contract — the per-scenario brake beside the tenant-wide kill-switch.
 */
@Service
public class Tmf915Service {

    private static final String BASE = "/tmf-api/aiManagement/v4";

    private final AiAuditRepository audits;
    private final AiBudgetRepository budgets;
    private final AiContractRepository contracts;
    private final ChurnModelRepository churnModels;
    private final TenantScope tenantScope;

    public Tmf915Service(AiAuditRepository audits, AiBudgetRepository budgets,
            AiContractRepository contracts, ChurnModelRepository churnModels,
            TenantScope tenantScope) {
        this.audits = audits;
        this.budgets = budgets;
        this.contracts = contracts;
        this.churnModels = churnModels;
        this.tenantScope = tenantScope;
    }

    /* ---------- aiModel: what has actually served ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listModels() {
        String tenant = tenantScope.currentTenantId();
        // (provider, model) -> tiers + scenarios, from the ledger
        Map<String, Map<String, Object>> byModel = new TreeMap<>();
        for (Object[] row : audits.servedModels(tenant)) {
            String provider = String.valueOf(row[0]);
            String model = String.valueOf(row[1]);
            String key = provider + "/" + model;
            Map<String, Object> view = byModel.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k);
                m.put("href", BASE + "/aiModel/" + k);
                m.put("name", model);
                m.put("provider", provider);
                m.put("category", "languageModel");
                m.put("tier", new LinkedHashSet<String>());
                m.put("servedContract", new LinkedHashSet<String>());
                m.put("@type", "AIModel");
                return m;
            });
            if (row[2] != null) {
                ((Set<String>) view.get("tier")).add(String.valueOf(row[2]));
            }
            if (row[3] != null) {
                ((Set<String>) view.get("servedContract")).add(String.valueOf(row[3]));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byModel.values());
        // the one genuinely versioned trained artifact: the churn model
        churnModels.findById(tenant).ifPresent(m -> out.add(churnModelView(m)));
        return out;
    }

    private Map<String, Object> churnModelView(ChurnModelRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", "local/churn-logistic");
        view.put("href", BASE + "/aiModel/local/churn-logistic");
        view.put("name", "churn-logistic");
        view.put("provider", "local");
        view.put("category", "trainedClassifier");
        view.put("trainingRecord", Map.of(
                "sampleCount", record.getSampleCount(),
                "positives", record.getPositives(),
                "trainedAt", record.getTrainedAt()));
        view.put("servedContract", List.of("churn-sweep"));
        view.put("@type", "AIModel");
        return view;
    }

    /* ---------- aiModelContract: the scenarios, with their numbers ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listContracts() {
        String tenant = tenantScope.currentTenantId();
        Map<String, AiContract> switches = new LinkedHashMap<>();
        for (AiContract c : contracts.findByTenantId(tenant)) {
            switches.put(c.getUseCase(), c);
        }
        Map<String, Map<String, Object>> metrics = new TreeMap<>();
        for (Object[] row : audits.contractMetrics(tenant)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", ((Number) row[1]).longValue());
            m.put("promptTokens", ((Number) row[2]).longValue());
            m.put("completionTokens", ((Number) row[3]).longValue());
            m.put("costMicros", ((Number) row[4]).longValue());
            m.put("avgLatencyMs", Math.round(((Number) row[5]).doubleValue()));
            metrics.put(String.valueOf(row[0]), m);
        }
        Map<String, Map<String, Long>> outcomes = new LinkedHashMap<>();
        for (Object[] row : audits.contractOutcomes(tenant)) {
            outcomes.computeIfAbsent(String.valueOf(row[0]), k -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        Map<String, Set<String>> models = new LinkedHashMap<>();
        for (Object[] row : audits.servedModels(tenant)) {
            if (row[3] != null) {
                models.computeIfAbsent(String.valueOf(row[3]), k -> new LinkedHashSet<>())
                        .add(row[0] + "/" + row[1]);
            }
        }
        AiBudget budget = budgets.findByTenantId(tenant).orElse(null);

        // a contract exists because it RAN (ledger) or was DECIDED (switch row)
        Set<String> useCases = new java.util.TreeSet<>(metrics.keySet());
        useCases.addAll(switches.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String useCase : useCases) {
            out.add(contractView(useCase, switches.get(useCase), metrics.get(useCase),
                    outcomes.get(useCase), models.get(useCase), budget));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findContract(String useCase) {
        return listContracts().stream()
                .filter(c -> useCase.equals(c.get("id")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no model contract '" + useCase + "' — contracts are born from use"));
    }

    /** The in-life lever: suspend or reactivate ONE scenario's contract. */
    @Transactional
    public Map<String, Object> patchContract(String useCase, Map<String, Object> patch) {
        String tenant = tenantScope.currentTenantId();
        Object state = patch.get("state");
        if (!"suspended".equals(state) && !"active".equals(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state must be 'suspended' or 'active'");
        }
        AiContract row = contracts.findByTenantIdAndUseCase(tenant, useCase)
                .orElseGet(() -> {
                    AiContract c = new AiContract();
                    c.setId(UUID.randomUUID().toString());
                    c.setTenantId(tenant);
                    c.setUseCase(useCase);
                    return c;
                });
        row.setEnabled("active".equals(state));
        row.setNote(patch.get("note") == null ? null : String.valueOf(patch.get("note")));
        row.setDecidedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        contracts.save(row);
        return findContract(useCase);
    }

    private Map<String, Object> contractView(String useCase, AiContract sw,
            Map<String, Object> metrics, Map<String, Long> outcomes,
            Set<String> servedBy, AiBudget budget) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", useCase);
        view.put("href", BASE + "/aiModelContract/" + useCase);
        view.put("name", useCase);
        boolean suspended = sw != null && !sw.isEnabled();
        boolean tenantDisabled = budget != null && !budget.isEnabled();
        view.put("state", suspended ? "suspended" : tenantDisabled ? "haltedByKillSwitch" : "active");
        if (sw != null) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("decidedAt", sw.getDecidedAt());
            if (sw.getNote() != null) {
                decision.put("note", sw.getNote());
            }
            view.put("lastDecision", decision);
        }
        if (servedBy != null && !servedBy.isEmpty()) {
            view.put("servedBy", servedBy.stream().map(id -> Map.of(
                    "id", id, "@referredType", "AIModel")).toList());
        }
        if (metrics != null) {
            Map<String, Object> monitoring = new LinkedHashMap<>(metrics);
            if (outcomes != null) {
                monitoring.put("outcome", outcomes);
            }
            view.put("monitoring", monitoring);
        }
        Map<String, Object> guardrail = new LinkedHashMap<>();
        guardrail.put("tenantKillSwitch", budget == null || budget.isEnabled() ? "armed" : "thrown");
        guardrail.put("budgetMicros", budget == null ? 0 : budget.getBudgetMicros());
        guardrail.put("windowHours", budget == null ? 720 : budget.getWindowHours());
        view.put("guardrail", guardrail);
        view.put("@type", "AIModelContract");
        return view;
    }
}
