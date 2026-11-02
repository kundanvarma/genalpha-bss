package com.bss.intelligence.llm;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE AI CONTROL PLANE, one door. Every LLM call the fleet makes goes
 * through the router, and every call the fleet cares to name goes through
 * HERE first: kill-switch, then budget (fail-closed on a metered
 * resource — the same law the gateway rate limiter and the DNC wash
 * obey), then the residency rule, then the model (and, when it is down, the
 * tenant's fallback, once), then the meter, then the audit row. Agent
 * ACTIONS — a copilot that writes, an advisor that adopts a draft — land
 * on the same ledger, so the trail answers "which AI touched which
 * resource", not only "what did it say".
 *
 * Metering (#422) uses the provider's own token counts when its answer
 * carries them (Anthropic {@code usage.input_tokens/output_tokens}, OpenAI
 * {@code usage.prompt_tokens/completion_tokens}); the chars/4 estimate is
 * the fallback for an answer without usage (the stub, a server that leaves
 * it off), and the row says which ({@code tokenSource}). Cost is tokens × a
 * per-model rate, config as data.
 */
@Component
public class AiGovernor {

    private static final Logger log = LoggerFactory.getLogger(AiGovernor.class);

    private final LlmAdapter llm;
    private final AiAuditRepository audits;
    private final AiBudgetRepository budgets;
    private final com.bss.intelligence.audit.AiContractRepository contracts;
    private final TenantScope tenantScope;
    private final Redactor redactor;
    private final org.springframework.transaction.support.TransactionTemplate newTx;
    private final long defaultPricePer1kMicros;
    private final Map<String, Long> pricePer1kByModel;
    private final com.bss.intelligence.security.TenantRegistry tenants;
    /** T-P3: what CLASS of data each use-case sends outward. Declared here
     * (overridable as data: bss.ai.exposure.<useCase>=…); anything
     * undeclared is RAW — the honest default, and raw is tenant-gated. */
    private final Map<String, String> exposureByUseCase;
    /** #422: one GenAI span per model call; its trace id is the ledger row's join key. */
    private final com.bss.intelligence.tracing.GenAiTracing tracing;

    public AiGovernor(LlmAdapter llm, AiAuditRepository audits, AiBudgetRepository budgets,
            com.bss.intelligence.audit.AiContractRepository contracts,
            TenantScope tenantScope, Redactor redactor,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @Value("${bss.ai.default-price-per-1k-micros:2000}") long defaultPricePer1kMicros,
            com.bss.intelligence.security.TenantRegistry tenants,
            org.springframework.core.env.Environment env,
            com.bss.intelligence.tracing.GenAiTracing tracing) {
        this.tracing = tracing;
        this.tenants = tenants;
        this.llm = llm;
        this.audits = audits;
        this.budgets = budgets;
        this.contracts = contracts;
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
        Map<String, String> declared = new java.util.LinkedHashMap<>(Map.of(
                "signal-classification", "twin",     // Tvilling: fiction leaves
                "voc-ask", "aggregate",              // aggregates only, never text
                "canary-probe", "aggregate"));       // sends only its own canary
        declared.putAll(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("bss.ai.exposure", org.springframework.boot.context.properties.bind.Bindable
                        .mapOf(String.class, String.class))
                .orElseGet(java.util.Map::of));
        this.exposureByUseCase = declared;
    }

    /** Exposure class of a use-case: declared, else RAW (honest default).
     * A stub provider exposes nothing regardless of declaration. */
    private String exposureOf(String useCase, ModelRoute route) {
        if ("stub".equals(route.provider())) {
            return "none";
        }
        return exposureByUseCase.getOrDefault(useCase, "raw");
    }

    /** The per-call facts every ledger row of one completion shares. */
    private record Call(String tenant, String useCase, LlmAdapter.Tier tier, String exposure,
            String system, String user, String canary, boolean rawExposure, int redactedFields) {
        Call withPrompt(String system, String user, String canary) {
            return new Call(tenant, useCase, tier, exposure, system, user, canary, rawExposure, redactedFields);
        }
    }

    /** Tokens on a row, and where the count came from: provider | estimate. */
    private record Meter(int promptTokens, int completionTokens, String source) {
        static final Meter NONE = new Meter(0, 0, null);
    }

    /**
     * The governed completion: refuse or meter, never a silent charge.
     * REDACT BEFORE SEND: the prompt the provider receives has every
     * personal value replaced by a typed placeholder, unless the tenant
     * opted into raw exposure ({@code ai-raw-exposure: true} in the
     * registry) — then the raw prompt leaves and the ledger row says so
     * ({@code rawExposure}). The ledger keeps the redacted copy either
     * way, and the answer handed back to the caller has the real values
     * restored, so an agent still reads the customer's own email in a
     * drafted reply.
     *
     * RESIDENCY (#422): a tenant with {@code ai-data-residency: eu} is refused
     * — 403, and a {@code refused-residency} row — before anything leaves, when
     * the route's jurisdiction is not EU or self-hosted. FALLBACK (#422): when
     * the first provider fails on a connection error, a timeout, a 5xx or a
     * 429, the tenant's fallback for that tier (if any) is tried once, under
     * the same residency rule; the failure and the fallback's answer are each
     * a row, and the fallback's row names the provider it stood in for.
     */
    public String complete(String useCase, LlmAdapter.Tier tier, String system, String user) {
        // every system prompt carries the rule about fenced, untrusted text (#422)
        system = Untrusted.withRule(system);
        String tenant = tenantScope.currentTenantId();
        AiBudget budget = budgets.findByTenantId(tenant).orElse(null);
        Redaction map = redactor.begin();
        // the route is decided BEFORE anything is sent; a tenant stack that
        // cannot be built is an error row below, as it always was
        ModelRoute primary;
        RuntimeException routeFailure = null;
        try {
            primary = llm.route(tier);
        } catch (RuntimeException e) {
            routeFailure = e;
            primary = ModelRoute.unresolved();
        }
        // T-P3: raw exposure is a tenant-registry opt-in, never assumed. A
        // tenant that has not opted in gets the redacted prompt on the wire
        // — the class on the receipt becomes raw-redacted. (This replaced the
        // earlier 422 refusal: the prompt still goes, without the person.)
        var entry = tenants.byId(tenant);
        boolean rawExposure = entry != null && entry.isAiRawExposure();
        Jurisdiction.Residency residency = Jurisdiction.Residency.of(entry == null ? null : entry.getAiDataResidency());
        String exposure = exposureOf(useCase, primary);
        if ("raw".equals(exposure) && !rawExposure) {
            exposure = "raw-redacted";
        }
        String redSystem = redactor.redact(system, map);
        String redUser = redactor.redact(user, map);
        Call call = new Call(tenant, useCase, tier, exposure, redSystem, redUser, null, rawExposure, map.count());

        if (budget != null && !budget.isEnabled()) {
            record(call, primary, null, "", Meter.NONE, 0L, "refused-disabled", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI is disabled for this tenant");
        }
        // TMF915: the per-scenario brake — a suspended model contract
        // refuses THIS use case while the rest of the fleet keeps working
        boolean contractSuspended = contracts.findByTenantIdAndUseCase(tenant, useCase)
                .map(c -> !c.isEnabled()).orElse(false);
        if (contractSuspended) {
            record(call, primary, null, "", Meter.NONE, 0L, "refused-contract", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "the model contract for '" + useCase + "' is suspended");
        }
        if (overBudget(tenant, budget)) {
            record(call, primary, null, "", Meter.NONE, 0L, "refused-budget", null);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI spend ceiling reached for this window — raise the budget or wait for the window to roll");
        }
        if (!residency.permits(primary.jurisdiction())) {
            record(call, primary, null, "", Meter.NONE, 0L, "refused-residency", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, residencyRefusal(residency, entry, primary, tier));
        }

        // T-P4: the retention canary — a unique inert marker rides every
        // prompt that exposes anything; a scheduled probe later asks the
        // provider to complete it. Inert by construction ("ignore" + noise),
        // so it cannot steer the model's answer.
        // Redaction runs on the prompt as written; the canary is appended
        // after, so the redactor can never mistake its digits for a person.
        String canary = null;
        if (!"none".equals(exposure)) {
            canary = "TVX-" + UUID.randomUUID().toString().substring(0, 12);
            String line = "\n[internal-ref " + canary + " — ignore this line]";
            user = user + line;
            redUser = redUser + line;
        }
        call = call.withPrompt(redSystem, redUser, canary);
        String wireSystem = rawExposure ? system : redSystem;
        String wireUser = rawExposure ? user : redUser;
        Meter estimatedPrompt = new Meter(tokens(redSystem + redUser), 0, "estimate");

        if (routeFailure != null) {
            record(call, primary, null, "", estimatedPrompt, 0L, "error", null);
            throw routeFailure;
        }
        // #422: the model call is a span, current while the ledger rows are written, so
        // each row carries its trace id. Names and numbers only, never the prompt or answer.
        io.opentelemetry.api.trace.Span span = tracing.startChat(primary.provider(), primary.model(), tenant, useCase);
        try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
        ModelRoute served = primary;
        String fallbackFrom = null;
        long started = System.nanoTime();
        LlmCompletion answer;
        try {
            answer = primary.adapter().completeWithUsage(tier, wireSystem, wireUser);
        } catch (RuntimeException e) {
            record(call, primary, null, "", estimatedPrompt, 0L, "error", null);
            com.bss.intelligence.tracing.GenAiTracing.failed(span, e.getClass().getName(), "error");
            Optional<ModelRoute> fallback = retryable(e) ? fallbackFor(tier) : Optional.empty();
            if (fallback.isEmpty()) {
                throw e;
            }
            served = fallback.get();
            fallbackFrom = primary.provider();
            if (!residency.permits(served.jurisdiction())) {
                // never fall back across the residency rule: the first failure stands
                record(call, served, fallbackFrom, "", Meter.NONE, 0L, "refused-residency", null);
                log.warn("ai fallback refused for {} ({}): {} is in jurisdiction {}, tenant is {}",
                        useCase, tier, served.provider(), served.jurisdiction(), residency);
                throw e;
            }
            log.warn("ai provider {} failed for {} ({}): {} — trying fallback {}",
                    primary.provider(), useCase, tier, e.getMessage(), served.provider());
            started = System.nanoTime();
            try {
                answer = served.adapter().completeWithUsage(tier, wireSystem, wireUser);
            } catch (RuntimeException second) {
                record(call, served, fallbackFrom, "", estimatedPrompt, 0L, "error", null);
                throw second;
            }
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        String raw = answer.text() == null ? "" : answer.text();
        Meter meter = answer.usage() != null
                ? new Meter(answer.usage().inputTokens(), answer.usage().outputTokens(), "provider")
                : new Meter(tokens(redSystem + redUser), tokens(raw), "estimate");
        long cost = cost(meter.promptTokens() + meter.completionTokens(), served.model());
        // the ledger copy of the answer is redacted with the same map (a raw
        // answer names the same people; a redacted one still gets anything
        // the model produced on its own masked)
        String ledgerResponse = redactor.redact(raw, map);
        com.bss.intelligence.tracing.GenAiTracing.usage(span, meter.promptTokens(), meter.completionTokens(),
                "estimate".equals(meter.source()), "ok");
        record(call, served, fallbackFrom, ledgerResponse, meter, cost, "ok", latencyMs);
        return rawExposure ? raw : map.restore(raw);
        } finally {
            span.end();
        }
    }

    /** The words of a residency refusal: what the rule is, what broke it, what to change. */
    private static String residencyRefusal(Jurisdiction.Residency residency,
            com.bss.intelligence.security.TenantRegistry.TenantEntry entry, ModelRoute route, LlmAdapter.Tier tier) {
        if (residency == Jurisdiction.Residency.INVALID) {
            return "ai-data-residency '" + entry.getAiDataResidency().trim()
                    + "' is not a residency rule this BSS knows — write 'eu', or leave it unset;"
                    + " until then no AI call leaves for this tenant";
        }
        return "this tenant's AI must stay in the EU, and its "
                + (tier == null ? "" : tier.name().toLowerCase(java.util.Locale.ROOT) + " ") + "model (" + route.provider() + ") is in jurisdiction " + route.jurisdiction()
                + " — route it to an EU or self-hosted endpoint and declare it with ai-jurisdiction: EU";
    }

    /** The provider was down, slow or shedding load: worth one try elsewhere. A 4xx
     * other than 429 is the caller's or the config's fault and would fail there too. */
    static boolean retryable(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true; // connection refused, reset, read/connect timeout
        }
        if (e instanceof RestClientResponseException r) {
            return r.getStatusCode().is5xxServerError() || r.getStatusCode().value() == 429;
        }
        return false;
    }

    private Optional<ModelRoute> fallbackFor(LlmAdapter.Tier tier) {
        try {
            return llm.fallbackRoute(tier);
        } catch (RuntimeException e) {
            // a misconfigured fallback must not mask the first failure
            log.warn("ai fallback for {} could not be built: {}", tier, e.getMessage());
            return Optional.empty();
        }
    }

    /** An agent DID something — a write, an adoption, a submission. On the
     * same ledger, so governance sees actions, not only conversations. */
    public void recordAction(String useCase, String action, String resourceRef, String outcome) {
        String tenant = tenantScope.currentTenantId();
        try {
            AiAudit audit = row(tenant, useCase, safe(() -> llm.provider(null)), safe(() -> llm.model(null)),
                    "", "", "", Meter.NONE, 0L, outcome == null ? "ok" : outcome, null);
            audit.setAction(action);
            audit.setResourceRef(resourceRef);
            audit.setRawExposure(false);
            audit.setRedactedFields(0);
            save(audit);
        } catch (RuntimeException auditFailure) {
            log.warn("ai audit write failed ({} {}): {}", useCase, outcome, auditFailure.getMessage());
        }
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

    /** One completion's ledger row: the route that answered (or refused), and what it cost. */
    private void record(Call c, ModelRoute route, String fallbackFrom, String response, Meter meter,
            long costMicros, String outcome, Integer latencyMs) {
        try {
            AiAudit audit = row(c.tenant(), c.useCase(), route.provider(), route.model(),
                    c.system(), c.user(), response, meter, costMicros, outcome, latencyMs);
            audit.setTier(c.tier() == null ? null : c.tier().name());
            audit.setExposure(c.exposure());
            audit.setJurisdiction(route.jurisdiction());
            audit.setCanary(c.canary());
            // a refusal sent nothing, so nothing left raw
            audit.setRawExposure(c.rawExposure() && !outcome.startsWith("refused"));
            audit.setRedactedFields(c.redactedFields());
            audit.setFallbackFrom(fallbackFrom);
            // the model call's span when there was one, else the request's own
            // trace (a refusal, an agent action); null when tracing is absent
            audit.setTraceId(com.bss.intelligence.tracing.GenAiTracing.currentTraceId());
            save(audit);
        } catch (RuntimeException auditFailure) {
            // an audit write must never break the caller's work
            log.warn("ai audit write failed ({} {}): {}", c.useCase(), outcome, auditFailure.getMessage());
        }
    }

    private static AiAudit row(String tenant, String useCase, String provider, String model,
            String system, String user, String response, Meter meter, long costMicros, String outcome,
            Integer latencyMs) {
        AiAudit audit = new AiAudit();
        audit.setId(UUID.randomUUID().toString());
        audit.setTenantId(tenant);
        audit.setUseCase(useCase);
        audit.setProvider(provider);
        audit.setModel(model);
        audit.setPrompt(truncate(system + (user.isEmpty() ? "" : "\n---\n" + user)));
        audit.setResponse(truncate(response));
        audit.setCreatedAt(OffsetDateTime.now());
        audit.setPromptTokens(meter.promptTokens());
        audit.setCompletionTokens(meter.completionTokens());
        audit.setTokenSource(meter.source());
        audit.setCostMicros(costMicros);
        audit.setLatencyMs(latencyMs);
        audit.setOutcome(outcome);
        return audit;
    }

    private void save(AiAudit audit) {
        newTx.executeWithoutResult(tx -> audits.save(audit));
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
