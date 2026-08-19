package com.bss.insight.service;

import com.bss.insight.entity.CustomerSignal;
import com.bss.insight.repository.CustomerSignalRepository;
import com.bss.insight.security.TenantScope;
import com.bss.insight.signal.RedactionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The signal store's front door (SI-P1): redact FIRST, then dedup, then
 * store. Raw text never touches the database — the {@link RedactionService}
 * result is the only text persisted, and the redaction audit rides the row.
 * Idempotent by (tenant, source, sourceRef|text) so connector re-syncs and
 * at-least-once event delivery never duplicate a signal.
 */
@Service
public class SignalService {

    private static final int MAX_TEXT = 4000;

    private final CustomerSignalRepository signals;
    private final RedactionService redaction;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public SignalService(CustomerSignalRepository signals, RedactionService redaction,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.signals = signals;
        this.redaction = redaction;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> ingest(Map<String, Object> dto) {
        String source = str(dto.get("source"));
        String rawText = str(dto.get("text"));
        if (source == null || source.isBlank() || rawText == null || rawText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and text are required");
        }
        String tenant = tenantScope.currentTenantId();

        RedactionService.Redacted clean = redaction.redact(
                rawText.length() > MAX_TEXT ? rawText.substring(0, MAX_TEXT) : rawText);

        String sourceRef = str(dto.get("sourceRef"));
        String hash = sha256(source + "|" + (sourceRef != null ? sourceRef : clean.text()));
        CustomerSignal existing = signals.findByTenantIdAndDedupHash(tenant, hash).orElse(null);
        if (existing != null) {
            return view(existing, true);
        }

        CustomerSignal s = new CustomerSignal();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(tenant);
        s.setSource(source);
        s.setSourceRef(sourceRef);
        s.setPartyId(str(dto.get("partyId")));
        s.setChannel(str(dto.get("channel")));
        s.setLang(str(dto.get("lang")));
        s.setText(clean.text());
        s.setContext(json(dto.get("context")));
        s.setRedactions(clean.counts().isEmpty() ? null : json(clean.counts()));
        s.setDedupHash(hash);
        s.setReceivedAt(OffsetDateTime.now());
        return view(signals.save(s), false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String source) {
        String tenant = tenantScope.currentTenantId();
        List<CustomerSignal> rows = source == null || source.isBlank()
                ? signals.findTop100ByTenantIdOrderByReceivedAtDesc(tenant)
                : signals.findTop100ByTenantIdAndSourceOrderByReceivedAtDesc(tenant, source);
        return rows.stream().map(s -> view(s, false)).toList();
    }

    private Map<String, Object> view(CustomerSignal s, boolean duplicate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("source", s.getSource());
        if (s.getSourceRef() != null) m.put("sourceRef", s.getSourceRef());
        if (s.getPartyId() != null) m.put("partyId", s.getPartyId());
        if (s.getChannel() != null) m.put("channel", s.getChannel());
        if (s.getLang() != null) m.put("lang", s.getLang());
        m.put("text", s.getText());
        if (s.getContext() != null) m.put("context", read(s.getContext()));
        if (s.getRedactions() != null) m.put("redactions", read(s.getRedactions()));
        m.put("receivedAt", s.getReceivedAt());
        if (duplicate) m.put("duplicate", true);
        m.put("@type", "CustomerSignal");
        return m;
    }

    private String json(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context is not serialisable");
        }
    }

    private Object read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return json;
        }
    }

    private static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
