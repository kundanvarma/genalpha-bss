package com.bss.intelligence.service;

import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContract;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.churn.ChurnModelRecord;
import com.bss.intelligence.churn.ChurnModelRepository;
import com.bss.intelligence.exception.NotFoundException;
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

    /** A served model accumulates its tiers and scenarios across ledger rows before it freezes. */
    private static final class ServedModel {
        final String provider;
        final String model;
        final Set<String> tiers = new LinkedHashSet<>();
        final Set<String> contracts = new LinkedHashSet<>();

        ServedModel(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        AiModelView view() {
            String key = provider + "/" + model;
            return new AiModelView(key, BASE + "/aiModel/" + key, model, provider, "languageModel", "active",
                    List.copyOf(tiers), null, List.copyOf(contracts), "AIModel");
        }
    }

    @Transactional(readOnly = true)
    public List<AiModelView> listModels() {
        String tenant = tenantScope.currentTenantId();
        // (provider, model) -> tiers + scenarios, from the ledger
        Map<String, ServedModel> byModel = new TreeMap<>();
        for (Object[] row : audits.servedModels(tenant)) {
            String provider = String.valueOf(row[0]);
            String model = String.valueOf(row[1]);
            ServedModel served = byModel.computeIfAbsent(provider + "/" + model,
                    k -> new ServedModel(provider, model));
            if (row[2] != null) {
                served.tiers.add(String.valueOf(row[2]));
            }
            if (row[3] != null) {
                served.contracts.add(String.valueOf(row[3]));
            }
        }
        List<AiModelView> out = new ArrayList<>(byModel.values().stream().map(ServedModel::view).toList());
        // the one genuinely versioned trained artifact: the churn model
        churnModels.findById(tenant).ifPresent(m -> out.add(churnModelView(m)));
        return out;
    }

    private AiModelView churnModelView(ChurnModelRecord record) {
        return new AiModelView("local/churn-logistic", BASE + "/aiModel/local/churn-logistic",
                "churn-logistic", "local", "trainedClassifier", "active", null,
                new AiModelView.TrainingRecord(record.getSampleCount(), record.getPositives(),
                        record.getTrainedAt()),
                List.of("churn-sweep"), "AIModel");
    }

    /** One served model by its ledger id (provider/model, or local/churn-logistic). */
    @Transactional(readOnly = true)
    public AiModelView findModel(String id) {
        return listModels().stream()
                .filter(m -> id.equals(m.id()))
                .findFirst()
                .orElseThrow(() -> NotFoundException.forResource("AiModel", id));
    }

    /* ---------- aiModelContract: the scenarios, with their numbers ---------- */

    @Transactional(readOnly = true)
    public List<AiModelContractView> listContracts() {
        String tenant = tenantScope.currentTenantId();
        Map<String, AiContract> switches = new LinkedHashMap<>();
        for (AiContract c : contracts.findByTenantId(tenant)) {
            switches.put(c.getUseCase(), c);
        }
        Map<String, Map<String, Long>> outcomes = new LinkedHashMap<>();
        for (Object[] row : audits.contractOutcomes(tenant)) {
            outcomes.computeIfAbsent(String.valueOf(row[0]), k -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        Map<String, AiModelContractView.Monitoring> metrics = new TreeMap<>();
        for (Object[] row : audits.contractMetrics(tenant)) {
            String useCase = String.valueOf(row[0]);
            metrics.put(useCase, new AiModelContractView.Monitoring(
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    Math.round(((Number) row[5]).doubleValue()),
                    outcomes.get(useCase)));
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
        List<AiModelContractView> out = new ArrayList<>();
        for (String useCase : useCases) {
            out.add(contractView(useCase, switches.get(useCase), metrics.get(useCase),
                    models.get(useCase), budget));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AiModelContractView findContract(String useCase) {
        return listContracts().stream()
                .filter(c -> useCase.equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no model contract '" + useCase + "' — contracts are born from use"));
    }

    /** The in-life lever: suspend or reactivate ONE scenario's contract. */
    @Transactional
    public AiModelContractView patchContract(String useCase, ContractPatch patch) {
        String tenant = tenantScope.currentTenantId();
        String state = patch == null ? null : patch.state();
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
        row.setNote(patch.note());
        row.setDecidedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        contracts.save(row);
        return findContract(useCase);
    }

    private AiModelContractView contractView(String useCase, AiContract sw,
            AiModelContractView.Monitoring monitoring, Set<String> servedBy, AiBudget budget) {
        boolean suspended = sw != null && !sw.isEnabled();
        boolean tenantDisabled = budget != null && !budget.isEnabled();
        String state = suspended ? "suspended" : tenantDisabled ? "haltedByKillSwitch" : "active";
        AiModelContractView.LastDecision decision = sw == null ? null
                : new AiModelContractView.LastDecision(sw.getDecidedAt(), sw.getNote());
        List<AiModelContractView.ModelRef> served = servedBy == null || servedBy.isEmpty() ? null
                : servedBy.stream().map(id -> new AiModelContractView.ModelRef(id, "AIModel")).toList();
        AiModelContractView.Guardrail guardrail = new AiModelContractView.Guardrail(
                budget == null || budget.isEnabled() ? "armed" : "thrown",
                budget == null ? 0 : budget.getBudgetMicros(),
                budget == null ? 720 : budget.getWindowHours());
        return new AiModelContractView(useCase, BASE + "/aiModelContract/" + useCase, useCase, state,
                decision, served, monitoring, guardrail, "AIModelContract");
    }
}
