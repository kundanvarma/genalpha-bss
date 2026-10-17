package com.bss.intelligence.door;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.exception.NotFoundException;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * THE MODEL DOOR (#283). A digital worker used to call its provider directly,
 * holding the tenant's key: the BSS could neither measure what it spent nor cap
 * it, and its prompts — customer ticket text — left unredacted. Now the worker
 * presents a key of its own here, where a provider key would go, and the door
 * forwards the call with the key the BSS holds, then records it from the
 * provider's OWN token counts, attributed to the worker.
 *
 * v0 (ticket 1): one Anthropic Messages call, forwarded as-is, metered and
 * ledgered. Guardrails (ceiling, kill-switch, model allowlist, no streaming)
 * and redaction land in the next tickets; until redaction does, the ledger
 * row says the exposure was RAW, because it was — and it keeps no content.
 */
@Service
public class ModelDoorService {

    private static final Logger log = LoggerFactory.getLogger(ModelDoorService.class);
    private static final String KEY_PREFIX = "bssw_";  // never sk-ant- / eyJ / cc-: those switch Hermes into Claude-login mode
    private static final String ANTHROPIC = "anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com";

    private final WorkerDoorKeyRepository keys;
    private final AiAuditRepository audits;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final DoorPricing pricing;
    private final RestClient.Builder http;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ModelDoorService(WorkerDoorKeyRepository keys, AiAuditRepository audits, TenantRegistry tenants,
            TenantScope tenantScope, DoorPricing pricing, RestClient.Builder http, ObjectMapper json) {
        this.keys = keys;
        this.audits = audits;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.pricing = pricing;
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

    /**
     * One Anthropic Messages call through the door. The presented key decides the
     * tenant — never a header — and the answer is the provider's, verbatim.
     */
    public DoorRequests.DoorReply anthropicMessages(String presentedKey, String body, String version, String beta) {
        Optional<WorkerDoorKey> found = lookup(presentedKey);
        if (found.isEmpty()) {
            return anthropicError(401, "authentication_error",
                    "unknown or revoked worker key — a worker calls its model through the door with the key it was hired with");
        }
        WorkerDoorKey key = found.get();
        try (TenantContext ignored = TenantContext.actAs(key.getTenantId())) {
            TenantRegistry.TenantEntry tenant = tenants.byId(key.getTenantId());
            if (tenant == null || !ANTHROPIC.equalsIgnoreCase(nullToEmpty(tenant.getWorkerAiProvider()))) {
                return anthropicError(400, "invalid_request_error",
                        "this tenant's worker model is not served at the Anthropic door");
            }
            if (nullToEmpty(tenant.getWorkerAiApiKey()).isBlank()) {
                return anthropicError(503, "api_error",
                        "this tenant has no worker model key configured — the operator sets worker-ai-api-key");
            }
            String model = requestedModel(body);
            long started = System.nanoTime();
            DoorRequests.DoorReply reply;
            try {
                reply = forward(tenant, body, version, beta);
            } catch (RuntimeException e) {
                log.warn("model door: upstream call failed for {}: {}", key.getBadge(), e.getMessage());
                ledger(key, model, new DoorPricing.Usage(0, 0, 0, 0), null, "upstream-error",
                        elapsedMs(started), "no response");
                return anthropicError(502, "api_error", "the worker model provider could not be reached");
            }
            DoorPricing.Usage usage = usageOf(reply.body());
            DoorPricing.Priced priced = pricing.price(model, usage);
            ledger(key, model, usage, priced, reply.status() < 300 ? "ok" : "http-" + reply.status(),
                    elapsedMs(started), summary(reply.body()));
            return reply;
        }
    }

    private DoorRequests.DoorReply forward(TenantRegistry.TenantEntry tenant, String body, String version, String beta) {
        String base = nullToEmpty(tenant.getWorkerAiBaseUrl()).isBlank() ? ANTHROPIC_BASE : tenant.getWorkerAiBaseUrl();
        return http.baseUrl(base.replaceAll("/+$", "")).build()
                .post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.set("x-api-key", tenant.getWorkerAiApiKey());
                    h.set("anthropic-version", version == null || version.isBlank() ? ANTHROPIC_VERSION : version);
                    if (beta != null && !beta.isBlank()) {
                        h.set("anthropic-beta", beta);
                    }
                })
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
     * CONTENT is kept — only its shape — because until redaction lands at the
     * door, that content is raw customer text.
     */
    private void ledger(WorkerDoorKey key, String model, DoorPricing.Usage usage, DoorPricing.Priced priced,
            String outcome, int latencyMs, String responseShape) {
        try {
            AiAudit row = new AiAudit();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(key.getTenantId());
            row.setExposure("raw");
            row.setRawExposure(true);
            row.setUseCase("workforce:" + (key.getJob() == null ? "worker" : key.getJob()));
            row.setProvider(ANTHROPIC);
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

    private DoorPricing.Usage usageOf(String responseBody) {
        try {
            JsonNode usage = json.readTree(responseBody).path("usage");
            return new DoorPricing.Usage(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0),
                    usage.path("cache_creation_input_tokens").asLong(0), usage.path("cache_read_input_tokens").asLong(0));
        } catch (RuntimeException e) {
            return new DoorPricing.Usage(0, 0, 0, 0);
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

    /** The shape of an answer, never its text: stop reason and block types. */
    private String summary(String responseBody) {
        try {
            JsonNode root = json.readTree(responseBody);
            StringBuilder s = new StringBuilder("stop=").append(root.path("stop_reason").asString(""));
            for (JsonNode block : root.path("content")) {
                s.append(' ').append(block.path("type").asString(""));
            }
            return s.length() > 4000 ? s.substring(0, 4000) : s.toString();
        } catch (RuntimeException e) {
            return "unreadable provider response";
        }
    }

    /** Anthropic's own error shape, so the worker's SDK reads a door refusal like a provider's. */
    private DoorRequests.DoorReply anthropicError(int status, String type, String message) {
        String body = json.createObjectNode().put("type", "error")
                .set("error", json.createObjectNode().put("type", type).put("message", message)).toString();
        return new DoorRequests.DoorReply(status, body);
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
