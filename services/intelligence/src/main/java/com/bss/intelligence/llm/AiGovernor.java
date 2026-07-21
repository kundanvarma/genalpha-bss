package com.bss.intelligence.llm;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * THE AI CONTROL PLANE, one door. Every LLM call the fleet makes goes
 * through the router, and every call the fleet cares to name goes through
 * HERE first: kill-switch, then budget (fail-closed on a metered
 * resource — the same law the gateway rate limiter and the DNC wash
 * obey), then the model, then the meter, then ONE audit row. Agent
 * ACTIONS — a copilot that writes, an advisor that adopts a draft — land
 * on the same ledger, so the trail answers "which AI touched which
 * resource", not only "what did it say".
 *
 * Metering estimates tokens from text (the chars/4 heuristic) so the stub
 * and every real provider meter identically; exact provider `usage` (on
 * the wire, currently discarded by the HTTP adapters) is a wired-later
 * seam. Cost is tokens × a per-model rate, config as data.
 */
@Component
public class AiGovernor {

    private static final Logger log = LoggerFactory.getLogger(AiGovernor.class);

    private final LlmAdapter llm;
    private final AiAuditRepository audits;
    private final AiBudgetRepository budgets;
    private final TenantScope tenantScope;
    private final Redactor redactor;
    private final org.springframework.transaction.support.TransactionTemplate newTx;
    private final long defaultPricePer1kMicros;
    private final Map<String, Long> pricePer1kByModel;

    public AiGovernor(LlmAdapter llm, AiAuditRepository audits, AiBudgetRepository budgets,
            TenantScope tenantScope, Redactor redactor,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @Value("${bss.ai.default-price-per-1k-micros:2000}") long defaultPricePer1kMicros,
            org.springframework.core.env.Environment env) {
        this.llm = llm;
        this.audits = audits;
        this.budgets = budgets;
        this.tenantScope = tenantScope;
        this.redactor = redactor;
        // ledger rows record what HAPPENED — they must survive whatever the
        // caller's transaction later decides (a refusal THROWS, and the
        // caller's rollback must not erase the refusal's own evidence).
        // Programmatic REQUIRES_NEW: the billing run's lesson, reapplied.
        this.newTx = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.defaultPricePer1kMicros = defaultPricePer1kMicros;
        // per-model overrides as data: bss.ai.prices.<model>=<micros/1k>
        this.pricePer1kByModel = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("bss.ai.prices", org.springframework.boot.context.properties.bind.Bindable
                        .mapOf(String.class, Long.class))
                .orElseGet(java.util.Map::of);
    }

    /**
     * The governed completion: refuse or meter, never a silent charge.
     * The prompt is redacted here too (defense in depth — call sites
     * redact what they know; the plane redacts what they missed).
     */
    public String complete(String useCase, LlmAdapter.Tier tier, String system, String user) {
        String tenant = tenantScope.currentTenantId();
        AiBudget budget = budgets.findByTenantId(tenant).orElse(null);

        if (budget != null && !budget.isEnabled()) {
            record(tenant, useCase, tier, system, user, "", 0, 0, 0L, "refused-disabled", null, null, null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI is disabled for this tenant");
        }
        if (overBudget(tenant, budget)) {
            record(tenant, useCase, tier, system, user, "", 0, 0, 0L, "refused-budget", null, null, null);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI spend ceiling reached for this window — raise the budget or wait for the window to roll");
        }

        String redSystem = redactor.redact(system);
        String redUser = redactor.redact(user);
        long started = System.nanoTime();
        String outcome = "ok";
        String raw = "";
        try {
            raw = llm.complete(tier, system, user);
        } catch (RuntimeException e) {
            outcome = "error";
            record(tenant, useCase, tier, redSystem, redUser, "",
                    tokens(redSystem + redUser), 0, 0L, outcome, null, null, null);
            throw e;
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        int promptTokens = tokens(redSystem + redUser);
        int completionTokens = tokens(raw);
        long cost = cost(promptTokens + completionTokens, safe(llm::model));
        record(tenant, useCase, tier, redSystem, redUser, redactor.redact(raw),
                promptTokens, completionTokens, cost, outcome, latencyMs, null, null);
        return raw;
    }

    /** An agent DID something — a write, an adoption, a submission. On the
     * same ledger, so governance sees actions, not only conversations. */
    public void recordAction(String useCase, String action, String resourceRef, String outcome) {
        String tenant = tenantScope.currentTenantId();
        record(tenant, useCase, null, "", "", "", 0, 0, 0,
                outcome == null ? "ok" : outcome, null, action, resourceRef);
    }

    /** True when this tenant's trailing-window spend has crossed its ceiling. */
    private boolean overBudget(String tenant, AiBudget budget) {
        if (budget == null || budget.getBudgetMicros() <= 0) {
            return false; // no row, or 0 = unlimited
        }
        OffsetDateTime since = OffsetDateTime.now().minusHours(budget.getWindowHours());
        return audits.sumCostSince(tenant, since) >= budget.getBudgetMicros();
    }

    private int tokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4); // chars/4, rounded up
    }

    /** Cost of a token count at this model's rate (per-model override, else default). */
    private long cost(int tokens, String model) {
        long per1k = pricePer1kByModel.getOrDefault(model, defaultPricePer1kMicros);
        return (long) tokens * per1k / 1000L;
    }

    private void record(String tenant, String useCase, LlmAdapter.Tier tier,
            String system, String user, String response, int promptTokens,
            int completionTokens, long costMicros, String outcome, Integer latencyMs,
            String action, String resourceRef) {
        try {
            AiAudit audit = new AiAudit();
            audit.setId(UUID.randomUUID().toString());
            audit.setTenantId(tenant);
            audit.setUseCase(useCase);
            audit.setProvider(safe(llm::provider));
            audit.setModel(safe(llm::model));
            audit.setPrompt(truncate(system + (user.isEmpty() ? "" : "\n---\n" + user)));
            audit.setResponse(truncate(response));
            audit.setCreatedAt(OffsetDateTime.now());
            audit.setTier(tier == null ? null : tier.name());
            audit.setPromptTokens(promptTokens);
            audit.setCompletionTokens(completionTokens);
            audit.setCostMicros(costMicros);
            audit.setLatencyMs(latencyMs);
            audit.setOutcome(outcome);
            audit.setAction(action);
            audit.setResourceRef(resourceRef);
            newTx.executeWithoutResult(tx -> audits.save(audit));
        } catch (RuntimeException auditFailure) {
            // an audit write must never break the caller's work
            log.warn("ai audit write failed ({} {}): {}", useCase, outcome, auditFailure.getMessage());
        }
    }

    private String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
