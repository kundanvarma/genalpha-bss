package com.bss.insight.service;

import com.bss.insight.dto.ConnectorSyncReceipt;
import com.bss.insight.dto.SignalConnectorRequest;
import com.bss.insight.dto.SignalConnectorView;
import com.bss.insight.dto.SignalInput;
import com.bss.insight.dto.SignalView;
import com.bss.insight.entity.SignalConnector;
import com.bss.insight.repository.SignalConnectorRepository;
import com.bss.insight.security.TenantScope;
import com.bss.insight.signal.SignalConnectorAdapter;
import com.bss.insight.signal.SignalConnectorRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's signal-connector bindings (SI-P2) — CRUD, the poll-mode
 * sync, and the generic inbound webhook. Bind a named adapter, or wire a
 * foreign push through 'http-webhook' with JSON pointers. Whatever the
 * road in, every item is ingested through SignalService — the PII firewall
 * has no side door.
 */
@Service
public class SignalConnectorService {

    private static final Set<String> KINDS = Set.of("servicedesk", "http-webhook", "slack-webhook");
    private static final Set<String> MODES = Set.of("poll", "webhook", "notify");

    private final SignalConnectorRepository connectors;
    private final SignalConnectorRegistry registry;
    private final SignalService signals;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public SignalConnectorService(SignalConnectorRepository connectors,
            SignalConnectorRegistry registry, SignalService signals,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.connectors = connectors;
        this.registry = registry;
        this.signals = signals;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    /* ---------- CRUD (back-office) ---------- */

    @Transactional(readOnly = true)
    public List<SignalConnectorView> list() {
        return connectors.findByTenantIdOrderByNameAsc(tenantScope.currentTenantId())
                .stream().map(SignalConnectorService::view).toList();
    }

    @Transactional
    public SignalConnectorView upsert(SignalConnectorRequest dto) {
        String name = dto.name();
        String kind = dto.kind();
        String source = dto.source();
        String mode = dto.mode();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (kind == null || !KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind is required and must be one of " + KINDS);
        }
        if (mode == null || !MODES.contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode is required and must be one of " + MODES);
        }
        if (source == null || source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source is required");
        }
        String tenant = tenantScope.currentTenantId();
        SignalConnector c = connectors.findByTenantIdAndName(tenant, name).orElseGet(() -> {
            SignalConnector fresh = new SignalConnector();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setName(name);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        c.setKind(kind);
        c.setSource(source);
        c.setMode(mode);
        c.setBaseUrl(dto.baseUrl());
        c.setSecretRef(dto.secretRef());
        c.setWebhookSecretRef(dto.webhookSecretRef());
        c.setConfig(json(dto.config()));
        c.setEnabled(!Boolean.FALSE.equals(dto.enabled()));
        c.setLastUpdate(OffsetDateTime.now());
        return view(connectors.save(c));
    }

    @Transactional
    public void delete(String name) {
        connectors.findByTenantIdAndName(tenantScope.currentTenantId(), name)
                .ifPresent(connectors::delete);
    }

    /* ---------- poll-mode sync ---------- */

    @Transactional
    public ConnectorSyncReceipt sync(String name) {
        SignalConnector c = required(name);
        if (!"poll".equals(c.getMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "connector '" + name + "' is webhook-mode — the source pushes, we don't pull");
        }
        SignalConnectorAdapter adapter = registry.get(c.getKind());
        if (adapter == null || !c.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "connector '" + name + "' has no enabled adapter");
        }
        int ingested = 0;
        int duplicates = 0;
        for (SignalInput item : adapter.pull(c)) {
            SignalView stored = signals.ingest(item.withSource(c.getSource()));
            if (stored.duplicated()) {
                duplicates++;
            } else {
                ingested++;
            }
        }
        c.setLastSyncAt(OffsetDateTime.now());
        connectors.save(c);
        return new ConnectorSyncReceipt(name, ingested, duplicates);
    }

    /* ---------- the generic inbound webhook ---------- */

    /**
     * A foreign system pushes its OWN shape; the connector's JSON pointers
     * map it (textPointer default /text, refPointer /id, partyPointer &
     * langPointer optional). The path is anonymous at the gateway — the
     * per-connector shared secret (env-var name in webhookSecretRef,
     * compared constant-time) is the door key.
     */
    @Transactional
    public SignalView webhook(String connectorId, String presentedSecret, String rawBody) {
        SignalConnector c = connectors.findByIdAndTenantId(connectorId, tenantScope.currentTenantId())
                .filter(SignalConnector::isEnabled)
                .filter(x -> "webhook".equals(x.getMode()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String secret = c.getWebhookSecretRef() == null ? ""
                : System.getenv().getOrDefault(c.getWebhookSecretRef(), "");
        if (secret.isBlank() || presentedSecret == null
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                        presentedSecret.getBytes(StandardCharsets.UTF_8))) {
            // an unbound secret NEVER opens the door — misconfig fails closed
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid JSON");
        }
        JsonNode cfg = readConfig(c);
        String text = at(body, cfg, "textPointer", "/text");
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no text at the configured pointer");
        }
        String ref = at(body, cfg, "refPointer", "/id");
        return signals.ingest(new SignalInput(c.getSource(), text, ref == null ? null : c.getName() + ":" + ref,
                at(body, cfg, "partyPointer", null), null, at(body, cfg, "langPointer", null), null));
    }

    private JsonNode readConfig(SignalConnector c) {
        try {
            return c.getConfig() == null ? objectMapper.createObjectNode()
                    : objectMapper.readTree(c.getConfig());
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String at(JsonNode body, JsonNode cfg, String key, String fallback) {
        String pointer = cfg.hasNonNull(key) ? cfg.get(key).asText() : fallback;
        if (pointer == null || pointer.isBlank()) {
            return null;
        }
        JsonNode v = body.at(pointer);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private SignalConnector required(String name) {
        return connectors.findByTenantIdAndName(tenantScope.currentTenantId(), name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "connector '" + name + "' is not configured for this tenant"));
    }

    /** The adapter's config as stored text: a JSON string is kept as-is, an object is serialised. */
    private String json(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config is not serialisable");
        }
    }

    /** Secrets are references only — values are never returned. */
    private static SignalConnectorView view(SignalConnector c) {
        return new SignalConnectorView(c.getId(), c.getName(), c.getKind(), c.getSource(), c.getMode(), c.getBaseUrl(),
                c.getSecretRef(), c.getWebhookSecretRef(), c.getConfig(), c.isEnabled(), c.getLastSyncAt(),
                "SignalConnector");
    }
}
