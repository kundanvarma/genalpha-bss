package com.bss.insight.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Set<String> KINDS = Set.of("servicedesk", "http-webhook");
    private static final Set<String> MODES = Set.of("poll", "webhook");

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
    public List<Map<String, Object>> list() {
        return connectors.findByTenantIdOrderByNameAsc(tenantScope.currentTenantId())
                .stream().map(SignalConnectorService::toMap).toList();
    }

    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String name = str(dto.get("name"));
        String kind = str(dto.get("kind"));
        String source = str(dto.get("source"));
        String mode = str(dto.get("mode"));
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
        c.setBaseUrl(str(dto.get("baseUrl")));
        c.setSecretRef(str(dto.get("secretRef")));
        c.setWebhookSecretRef(str(dto.get("webhookSecretRef")));
        c.setConfig(json(dto.get("config")));
        c.setEnabled(!Boolean.FALSE.equals(dto.get("enabled")));
        c.setLastUpdate(OffsetDateTime.now());
        return toMap(connectors.save(c));
    }

    @Transactional
    public void delete(String name) {
        connectors.findByTenantIdAndName(tenantScope.currentTenantId(), name)
                .ifPresent(connectors::delete);
    }

    /* ---------- poll-mode sync ---------- */

    @Transactional
    public Map<String, Object> sync(String name) {
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
        for (Map<String, Object> item : adapter.pull(c)) {
            Map<String, Object> dto = new LinkedHashMap<>(item);
            dto.put("source", c.getSource());
            Map<String, Object> stored = signals.ingest(dto);
            if (Boolean.TRUE.equals(stored.get("duplicate"))) {
                duplicates++;
            } else {
                ingested++;
            }
        }
        c.setLastSyncAt(OffsetDateTime.now());
        connectors.save(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", name);
        out.put("ingested", ingested);
        out.put("duplicates", duplicates);
        return out;
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
    public Map<String, Object> webhook(String connectorId, String presentedSecret, String rawBody) {
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
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("source", c.getSource());
        dto.put("text", text);
        String ref = at(body, cfg, "refPointer", "/id");
        if (ref != null) dto.put("sourceRef", c.getName() + ":" + ref);
        String party = at(body, cfg, "partyPointer", null);
        if (party != null) dto.put("partyId", party);
        String lang = at(body, cfg, "langPointer", null);
        if (lang != null) dto.put("lang", lang);
        return signals.ingest(dto);
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

    private String json(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config is not serialisable");
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** Secrets are references only — values are never returned. */
    private static Map<String, Object> toMap(SignalConnector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("kind", c.getKind());
        m.put("source", c.getSource());
        m.put("mode", c.getMode());
        if (c.getBaseUrl() != null) m.put("baseUrl", c.getBaseUrl());
        if (c.getSecretRef() != null) m.put("secretRef", c.getSecretRef());
        if (c.getWebhookSecretRef() != null) m.put("webhookSecretRef", c.getWebhookSecretRef());
        if (c.getConfig() != null) m.put("config", c.getConfig());
        m.put("enabled", c.isEnabled());
        if (c.getLastSyncAt() != null) m.put("lastSyncAt", c.getLastSyncAt());
        m.put("@type", "SignalConnector");
        return m;
    }
}
