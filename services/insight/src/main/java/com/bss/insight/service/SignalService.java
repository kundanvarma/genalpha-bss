package com.bss.insight.service;

import com.bss.insight.entity.CustomerSignal;
import com.bss.insight.entity.SignalClassification;
import com.bss.insight.events.DomainEventPublisher;
import com.bss.insight.repository.CustomerSignalRepository;
import com.bss.insight.repository.SignalClassificationRepository;
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
    private final SignalClassificationRepository classifications;
    private final RedactionService redaction;
    private final PartyTraitService traits;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public SignalService(CustomerSignalRepository signals,
            SignalClassificationRepository classifications, RedactionService redaction,
            PartyTraitService traits, DomainEventPublisher events,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.signals = signals;
        this.classifications = classifications;
        this.redaction = redaction;
        this.traits = traits;
        this.events = events;
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
    public List<Map<String, Object>> list(String source, boolean unclassifiedOnly) {
        String tenant = tenantScope.currentTenantId();
        List<CustomerSignal> rows = source == null || source.isBlank()
                ? signals.findTop100ByTenantIdOrderByReceivedAtDesc(tenant)
                : signals.findTop100ByTenantIdAndSourceOrderByReceivedAtDesc(tenant, source);
        Map<String, SignalClassification> byId = classifications
                .findByTenantIdAndSignalIdIn(tenant, rows.stream().map(CustomerSignal::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        SignalClassification::getSignalId, java.util.function.Function.identity()));
        return rows.stream()
                .filter(r -> !unclassifiedOnly || !byId.containsKey(r.getId()))
                .map(r -> {
                    Map<String, Object> v = view(r, false);
                    SignalClassification c = byId.get(r.getId());
                    if (c != null) {
                        v.put("classification", classificationView(c));
                    }
                    return v;
                }).toList();
    }

    /**
     * The battery's write-back (SI-P3) — with the receipts checked HERE, at
     * the store: every non-null evidence quote must appear VERBATIM in the
     * signal's redacted text, or the whole classification is refused (422).
     * A model that cannot cite its source does not get a row. An accepted
     * churn signal marks the party's trait, and the event goes on the bus.
     */
    @Transactional
    public Map<String, Object> classify(String signalId, Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        CustomerSignal signal = signals.findById(signalId)
                .filter(x -> tenant.equals(x.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no signal '" + signalId + "'"));
        Map<String, Object> evidence = dto.get("evidence") instanceof Map<?, ?> e
                ? castMap(e) : Map.of();
        if (evidence.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "a classification without evidence quotes is not accepted");
        }
        for (Map.Entry<String, Object> quote : evidence.entrySet()) {
            if (quote.getValue() == null) {
                continue;
            }
            if (!signal.getText().contains(String.valueOf(quote.getValue()))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "evidence for '" + quote.getKey() + "' is not a verbatim quote from the signal");
            }
        }
        SignalClassification c = classifications.findByTenantIdAndSignalId(tenant, signalId)
                .orElseGet(() -> {
                    SignalClassification fresh = new SignalClassification();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setSignalId(signalId);
                    return fresh;
                });
        c.setSentiment(str(dto.get("sentiment")));
        c.setAspect(str(dto.get("aspect")));
        c.setCategory(str(dto.get("category")));
        c.setPainPoint(str(dto.get("painPoint")));
        c.setPainImpact(dto.get("painImpact") instanceof Number n ? n.intValue() : null);
        c.setLoyaltyIndicator(str(dto.get("loyaltyIndicator")));
        c.setChurnSignal(Boolean.TRUE.equals(dto.get("churnSignal")));
        c.setChurnReason(str(dto.get("churnReason")));
        c.setEvidence(json(evidence));
        c.setProvider(str(dto.get("provider")));
        c.setModel(str(dto.get("model")));
        c.setClassifiedAt(OffsetDateTime.now());
        classifications.save(c);

        if (c.isChurnSignal() && signal.getPartyId() != null) {
            traits.setTrait(signal.getPartyId(), "churnSignal", "true");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("signalId", signalId);
        event.put("source", signal.getSource());
        if (signal.getPartyId() != null) {
            event.put("partyId", signal.getPartyId());
        }
        event.put("sentiment", c.getSentiment());
        event.put("aspect", c.getAspect());
        event.put("category", c.getCategory());
        event.put("churnSignal", c.isChurnSignal());
        events.publish("SignalClassifiedEvent", "signalClassification", event);

        return classificationView(c);
    }

    private Map<String, Object> classificationView(SignalClassification c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.getSentiment() != null) m.put("sentiment", c.getSentiment());
        if (c.getAspect() != null) m.put("aspect", c.getAspect());
        if (c.getCategory() != null) m.put("category", c.getCategory());
        if (c.getPainPoint() != null) m.put("painPoint", c.getPainPoint());
        if (c.getPainImpact() != null) m.put("painImpact", c.getPainImpact());
        if (c.getLoyaltyIndicator() != null) m.put("loyaltyIndicator", c.getLoyaltyIndicator());
        m.put("churnSignal", c.isChurnSignal());
        if (c.getChurnReason() != null) m.put("churnReason", c.getChurnReason());
        if (c.getEvidence() != null) m.put("evidence", read(c.getEvidence()));
        if (c.getProvider() != null) m.put("provider", c.getProvider());
        if (c.getModel() != null) m.put("model", c.getModel());
        m.put("classifiedAt", c.getClassifiedAt());
        m.put("@type", "SignalClassification");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
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
