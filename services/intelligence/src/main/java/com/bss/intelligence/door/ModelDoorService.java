package com.bss.intelligence.door;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.exception.NotFoundException;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * THE MODEL DOOR (#283). A digital worker used to call its provider directly,
 * holding the tenant's key: the BSS could neither measure what it spent nor cap
 * it, and its prompts — customer ticket text — left unredacted. Now the worker
 * presents a key of its own here, where a provider key would go, and the door
 * forwards the call with the key the BSS holds, then records it from the
 * provider's OWN token counts, attributed to the worker.
 *
 * Guardrails (ticket 2): the kill-switch, the tenant's ceiling and the
 * workers' own ceiling refuse with 402 and billing wording — the status and the
 * words a worker's SDK treats as a hard wall, so the shift ENDS instead of
 * retrying into it. Only the tenant's worker model (and the provider's small
 * side-call model) is served, and streaming is refused in words the SDK reads
 * as "stream not supported", so it falls back to a plain call. Every refusal is
 * a ledger row too.
 *
 * Redaction (ticket 3): the call is redacted on the way out and restored on the
 * way back (see DoorRedaction) with the same redactor every other model call in
 * the BSS uses, unless the tenant opted into raw exposure — and the ledger row
 * says which, and how many values were replaced. It still keeps no content.
 */
@Service
public class ModelDoorService {

    private static final Logger log = LoggerFactory.getLogger(ModelDoorService.class);
    private static final String KEY_PREFIX = "bssw_";  // never sk-ant- / eyJ / cc-: those switch Hermes into Claude-login mode

    private final WorkerDoorKeyRepository keys;
    private final AiAuditRepository audits;
    private final AiBudgetRepository budgets;
    private final Map<String, Set<String>> sideModels;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final DoorPricing pricing;
    private final Redactor redactor;
    private final RestClient.Builder http;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ModelDoorService(WorkerDoorKeyRepository keys, AiAuditRepository audits, AiBudgetRepository budgets,
            TenantRegistry tenants, TenantScope tenantScope, DoorPricing pricing, Redactor redactor,
            RestClient.Builder http, ObjectMapper json,
            // the small model a provider's agent framework uses for its own side calls
            // (titles, compression) — served beside the tenant's worker model, and metered
            @Value("${bss.ai.door-side-models.anthropic:claude-haiku-4-5-20251001}") String anthropicSideModels,
            @Value("${bss.ai.door-side-models.openai:}") String openAiSideModels) {
        this.keys = keys;
        this.audits = audits;
        this.budgets = budgets;
        this.sideModels = Map.of("anthropic", modelSet(anthropicSideModels), "openai", modelSet(openAiSideModels));
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.pricing = pricing;
        this.redactor = redactor;
        this.http = http;
        this.json = json;
    }

    /* ---------------------------------------------------------------- keys */

    /** Issue a key for one worker, in the CALLER's tenant. Returned once; only its hash is kept. */
    public DoorRequests.IssuedKey issue(DoorRequests.IssueKey request) {
        if (request == null || request.badge() == null || request.badge().isBlank()) {
            throw new BadRequestException("badge is required — a door key belongs to one worker");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        WorkerDoorKey row = new WorkerDoorKey();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setKeyHash(sha256(key));
        row.setBadge(request.badge().trim());
        row.setWorkerId(blankToNull(request.workerId()));
        row.setJob(blankToNull(request.job()));
        row.setCreatedAt(OffsetDateTime.now());
        keys.save(row);
        return new DoorRequests.IssuedKey(row.getId(), key, row.getBadge(), row.getWorkerId(), row.getJob(),
                row.getCreatedAt());
    }

    /** Revoke a key in the caller's tenant: the next call through the door is refused. */
    public void revoke(String keyId) {
        WorkerDoorKey row = keys.findByIdAndTenantId(keyId, tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("door key " + keyId + " not found"));
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(OffsetDateTime.now());
            keys.save(row);
        }
    }

    /* ---------------------------------------------------------------- door */

    static final DoorProtocol ANTHROPIC_DOOR = new AnthropicProtocol();
    static final DoorProtocol OPENAI_DOOR = new OpenAiProtocol();

    /** Anthropic Messages, as the worker's SDK sends it. */
    public DoorRequests.DoorReply anthropicMessages(String presentedKey, String body, String version, String beta) {
        Map<String, String> passthrough = new HashMap<>();
        if (version != null) {
            passthrough.put("anthropic-version", version);
        }
        if (beta != null) {
            passthrough.put("anthropic-beta", beta);
        }
        return call(ANTHROPIC_DOOR, presentedKey, body, passthrough);
    }

    /** OpenAI Chat Completions (#309): the worker's key arrives as a Bearer token. */
    public DoorRequests.DoorReply openAiChat(String authorization, String body) {
        String key = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        return call(OPENAI_DOOR, key, body, Map.of());
    }

    /**
     * One call through the door, in the given provider's API. The presented key
     * decides the tenant — never a header — and the answer is the provider's,
     * with the placeholders reversed.
     */
    DoorRequests.DoorReply call(DoorProtocol protocol, String presentedKey, String body,
            Map<String, String> passthrough) {
        Optional<WorkerDoorKey> found = lookup(presentedKey);
        if (found.isEmpty()) {
            return error(protocol, 401, DoorProtocol.Refusal.AUTH,
                    "unknown or revoked worker key — a worker calls its model through the door with the key it was hired with");
        }
        WorkerDoorKey key = found.get();
        try (TenantContext ignored = TenantContext.actAs(key.getTenantId())) {
            TenantRegistry.TenantEntry tenant = tenants.byId(key.getTenantId());
            if (tenant == null || !protocol.serves(nullToEmpty(tenant.getWorkerAiProvider()))) {
                return error(protocol, 400, DoorProtocol.Refusal.INVALID,
                        "this tenant's worker model (" + (tenant == null ? "none" : tenant.getWorkerAiProvider())
                                + ") is not served at the " + protocol.name() + " door");
            }
            if (nullToEmpty(tenant.getWorkerAiApiKey()).isBlank()) {
                return error(protocol, 503, DoorProtocol.Refusal.UNAVAILABLE,
                        "this tenant has no worker model key configured — the operator sets worker-ai-api-key");
            }
            String model = requestedModel(body);
            Optional<DoorRequests.DoorReply> refused = guard(protocol, key, tenant, body, model);
            if (refused.isPresent()) {
                return refused.get();
            }
            // redact on the way out, unless the tenant opted into raw exposure
            boolean raw = tenant.isAiRawExposure();
            Redaction map = redactor.begin();
            String outbound = body;
            if (!raw) {
                JsonNode tree = json.readTree(body == null ? "{}" : body);
                protocol.redact(tree, redactor, map);
                outbound = tree.toString();
            }
            Exposure exposure = new Exposure(raw ? "raw" : "raw-redacted", raw, map.count());
            long started = System.nanoTime();
            DoorRequests.DoorReply reply;
            try {
                reply = forward(protocol, tenant, outbound, passthrough);
            } catch (RuntimeException e) {
                log.warn("model door: upstream call failed for {}: {}", key.getBadge(), e.getMessage());
                ledger(protocol, key, model, new DoorPricing.Usage(0, 0, 0, 0), null, "upstream-error",
                        elapsedMs(started), "no response", exposure);
                return error(protocol, 502, DoorProtocol.Refusal.UPSTREAM, "the worker model provider could not be reached");
            }
            JsonNode answer = readOrNull(reply.body());
            DoorPricing.Usage usage = answer == null ? new DoorPricing.Usage(0, 0, 0, 0) : protocol.usage(answer);
            DoorPricing.Priced priced = pricing.price(model, usage);
            ledger(protocol, key, model, usage, priced, reply.status() < 300 ? "ok" : "http-" + reply.status(),
                    elapsedMs(started), answer == null ? "unreadable provider response" : cap(protocol.summary(answer)),
                    exposure);
            // and restore on the way back, so the worker only ever holds real values
            if (!raw && map.count() > 0 && reply.status() < 300 && answer != null) {
                protocol.restore(answer, map);
                reply = new DoorRequests.DoorReply(reply.status(), answer.toString());
            }
            return reply;
        }
    }

    /**
     * The door's guardrails, in order: the kill-switch, the tenant's ceiling, the
     * workers' own ceiling, then the request itself (no streaming, a served model).
     * A refusal is a ledger row and a reply in the provider's own error shape.
     */
    private Optional<DoorRequests.DoorReply> guard(DoorProtocol protocol, WorkerDoorKey key,
            TenantRegistry.TenantEntry tenant, String body, String model) {
        AiBudget budget = budgets.findByTenantId(key.getTenantId()).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        if (budget != null && !budget.isEnabled()) {
            return refuse(protocol, key, model, "refused-disabled", 402, DoorProtocol.Refusal.BILLING,
                    "AI is switched off for this tenant (kill-switch) — billing hard limit: no worker model calls"
                            + " until an operator switches it back on");
        }
        if (budget != null && budget.getBudgetMicros() > 0
                && audits.sumCostSince(key.getTenantId(), now.minusHours(budget.getWindowHours()))
                        >= budget.getBudgetMicros()) {
            return refuse(protocol, key, model, "refused-budget", 402, DoorProtocol.Refusal.BILLING,
                    "this tenant's AI spend ceiling is reached — billing hard limit for this window");
        }
        if (budget != null && budget.getWorkerBudgetMicros() > 0
                && audits.sumWorkerCostSince(key.getTenantId(), now.minusHours(budget.getWorkerWindowHours()))
                        >= budget.getWorkerBudgetMicros()) {
            return refuse(protocol, key, model, "refused-worker-budget", 402, DoorProtocol.Refusal.BILLING,
                    "the workers' spend ceiling is reached — billing hard limit for this window");
        }
        if (requestsStream(body)) {
            // the SDK falls back to a plain call on an error naming "stream" and "not supported"
            return refuse(protocol, key, model, "refused-stream", 400, DoorProtocol.Refusal.INVALID,
                    "streaming is not supported at the model door — send the request without stream");
        }
        Set<String> served = new LinkedHashSet<>(sideModels.getOrDefault(protocol.name(), Set.of()));
        if (tenant.getWorkerAiModel() != null && !tenant.getWorkerAiModel().isBlank()) {
            served.add(tenant.getWorkerAiModel().trim());
        }
        if (model == null || !served.contains(model)) {
            return refuse(protocol, key, model, "refused-model", 400, DoorProtocol.Refusal.INVALID,
                    "model '" + model + "' is not this tenant's worker model — the door serves " + served);
        }
        return Optional.empty();
    }

    private Optional<DoorRequests.DoorReply> refuse(DoorProtocol protocol, WorkerDoorKey key, String model,
            String outcome, int status, DoorProtocol.Refusal kind, String message) {
        ledger(protocol, key, model, new DoorPricing.Usage(0, 0, 0, 0), null, outcome, 0, message, Exposure.NOTHING_SENT);
        return Optional.of(error(protocol, status, kind, message));
    }

    private boolean requestsStream(String body) {
        try {
            return json.readTree(body == null ? "{}" : body).path("stream").asBoolean(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The provider's model listing is not served here: a worker learns its model from its config. */
    public DoorRequests.DoorReply modelsNotServed(boolean openAi) {
        return error(openAi ? OPENAI_DOOR : ANTHROPIC_DOOR, 404, DoorProtocol.Refusal.NOT_FOUND,
                "the model door does not list models");
    }

    private DoorRequests.DoorReply forward(DoorProtocol protocol, TenantRegistry.TenantEntry tenant, String body,
            Map<String, String> passthrough) {
        String base = nullToEmpty(tenant.getWorkerAiBaseUrl()).isBlank() ? protocol.defaultBase()
                : tenant.getWorkerAiBaseUrl();
        return http.baseUrl(base.replaceAll("/+$", "")).build()
                .post().uri(protocol.path())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> protocol.authenticate(h, tenant.getWorkerAiApiKey(), passthrough))
                .body(body == null ? "" : body)
                .exchange((req, res) -> new DoorRequests.DoorReply(res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** The key is resolved as the SYSTEM tenant: the door cannot know the tenant before it has read the key. */
    private Optional<WorkerDoorKey> lookup(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try (TenantContext ignored = TenantContext.actAsSystem()) {
            return keys.findByKeyHash(sha256(presentedKey)).filter(k -> k.getRevokedAt() == null);
        }
    }

    /* ------------------------------------------------------------- the ledger */

    /**
     * One ledger row per call, the worker as its actor. No prompt or response
     * CONTENT is kept — only its shape: a worker's call is a whole conversation
     * of customer text, and the ledger does not need it to say what it cost.
     */
    /** What left on a call: its exposure class, whether it was the tenant's raw opt-in, how many values were replaced. */
    private record Exposure(String exposureClass, boolean raw, int redactedFields) {
        static final Exposure NOTHING_SENT = new Exposure("none", false, 0);
    }

    private void ledger(DoorProtocol protocol, WorkerDoorKey key, String model, DoorPricing.Usage usage,
            DoorPricing.Priced priced, String outcome, int latencyMs, String responseShape, Exposure exposure) {
        try {
            AiAudit row = new AiAudit();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(key.getTenantId());
            row.setExposure(exposure.exposureClass());
            row.setRawExposure(exposure.raw());
            row.setRedactedFields(exposure.redactedFields());
            row.setUseCase("workforce:" + (key.getJob() == null ? "worker" : key.getJob()));
            row.setProvider(protocol.name());
            row.setModel(model);
            row.setPrompt("worker model call through the door — content not retained");
            row.setResponse(responseShape);
            row.setCreatedAt(OffsetDateTime.now());
            row.setPromptTokens((int) Math.min(Integer.MAX_VALUE, usage.allInput()));
            row.setCompletionTokens((int) Math.min(Integer.MAX_VALUE, usage.outputTokens()));
            row.setCostMicros(priced == null ? 0L : priced.costMicros());
            row.setLatencyMs(latencyMs);
            row.setOutcome(outcome);
            row.setResourceRef("doorKey:" + key.getId() + (priced != null && priced.defaulted() ? " price=default" : ""));
            row.setActorId(key.getWorkerId() != null ? key.getWorkerId() : key.getId());
            row.setActorName(key.getBadge());
            audits.save(row);
        } catch (RuntimeException e) {
            // the call already happened; a lost row is logged loudly rather than failing the worker
            log.error("model door: ledger write failed for {} ({}): {}", key.getBadge(), outcome, e.getMessage());
        }
    }

    private String requestedModel(String body) {
        try {
            JsonNode model = json.readTree(body == null ? "{}" : body).path("model");
            return model.isString() ? model.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private DoorRequests.DoorReply error(DoorProtocol protocol, int status, DoorProtocol.Refusal kind, String message) {
        return new DoorRequests.DoorReply(status, protocol.error(json, kind, message));
    }

    private JsonNode readOrNull(String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cap(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static Set<String> modelSet(String commaSeparated) {
        return new LinkedHashSet<>(Arrays.stream(commaSeparated.split(","))
                .map(String::trim).filter(m -> !m.isEmpty()).toList());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
