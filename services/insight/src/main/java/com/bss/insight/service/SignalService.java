package com.bss.insight.service;

import com.bss.insight.dto.ClassificationInput;
import com.bss.insight.dto.FlywheelPair;
import com.bss.insight.dto.SignalClassificationView;
import com.bss.insight.dto.SignalInput;
import com.bss.insight.dto.SignalTwin;
import com.bss.insight.dto.SignalView;
import com.bss.insight.entity.CustomerSignal;
import com.bss.insight.entity.SignalClassification;
import com.bss.insight.events.DomainEventPublisher;
import com.bss.insight.repository.CustomerSignalRepository;
import com.bss.insight.repository.SignalClassificationRepository;
import com.bss.insight.security.TenantScope;
import com.bss.insight.entity.TwinVault;
import com.bss.insight.repository.TwinVaultRepository;
import com.bss.insight.signal.TwinningService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
 * store. Raw text never touches the database — the twinning result is the
 * only text persisted, and the redaction audit rides the row. Idempotent by
 * (tenant, source, sourceRef|text) so connector re-syncs and at-least-once
 * event delivery never duplicate a signal.
 */
@Service
public class SignalService {

    private static final int MAX_TEXT = 4000;

    private final CustomerSignalRepository signals;
    private final SignalClassificationRepository classifications;
    private final TwinningService twinning;
    private final TwinVaultRepository twins;
    private final PartyTraitService traits;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public SignalService(CustomerSignalRepository signals,
            SignalClassificationRepository classifications, TwinningService twinning,
            TwinVaultRepository twins, PartyTraitService traits, DomainEventPublisher events,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.signals = signals;
        this.classifications = classifications;
        this.twinning = twinning;
        this.twins = twins;
        this.traits = traits;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SignalView ingest(SignalInput dto) {
        String source = dto.source();
        String rawText = dto.text();
        if (source == null || source.isBlank() || rawText == null || rawText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and text are required");
        }
        String tenant = tenantScope.currentTenantId();

        // Tvilling T-P1: ONE pass yields both protections — the redacted text
        // the store keeps AND the twin (same story, nobody real) for T-P2's
        // frontier path. The per-signal key makes the same customer a
        // DIFFERENT fiction in every signal.
        String twinKey = UUID.randomUUID().toString();
        TwinningService.Twinned clean = twinning.twin(
                rawText.length() > MAX_TEXT ? rawText.substring(0, MAX_TEXT) : rawText, twinKey);

        String sourceRef = dto.sourceRef();
        String hash = sha256(source + "|" + (sourceRef != null ? sourceRef : clean.redacted()));
        CustomerSignal existing = signals.findByTenantIdAndDedupHash(tenant, hash).orElse(null);
        if (existing != null) {
            return view(existing, true);
        }

        CustomerSignal s = new CustomerSignal();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(tenant);
        s.setSource(source);
        s.setSourceRef(sourceRef);
        s.setPartyId(dto.partyId());
        s.setChannel(dto.channel());
        s.setLang(dto.lang());
        s.setText(clean.redacted());
        s.setTwinText(clean.twin());
        s.setContext(json(dto.context()));
        s.setRedactions(clean.counts().isEmpty() ? null : json(clean.counts()));
        s.setDedupHash(hash);
        s.setReceivedAt(OffsetDateTime.now());
        CustomerSignal saved = signals.save(s);
        TwinVault vault = new TwinVault();
        vault.setId(UUID.randomUUID().toString());
        vault.setTenantId(tenant);
        vault.setSignalId(saved.getId());
        vault.setPartyId(saved.getPartyId());
        vault.setTwinKey(twinKey);
        vault.setOffsetMap(json(clean.offsetMap()));
        vault.setCreatedAt(OffsetDateTime.now());
        twins.save(vault);
        return view(saved, false);
    }

    @Transactional(readOnly = true)
    public List<SignalView> list(String source, boolean unclassifiedOnly) {
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
                    SignalView v = view(r, false);
                    SignalClassification c = byId.get(r.getId());
                    return c == null ? v : v.withClassification(classificationView(c));
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
    public SignalClassificationView classify(String signalId, ClassificationInput dto) {
        String tenant = tenantScope.currentTenantId();
        CustomerSignal signal = signals.findById(signalId)
                .filter(x -> tenant.equals(x.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no signal '" + signalId + "'"));
        Map<String, String> evidence = new LinkedHashMap<>();
        if (dto.evidence() != null && dto.evidence().isObject()) {
            for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) dto.evidence()::fields) {
                JsonNode v = e.getValue();
                evidence.put(e.getKey(), v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString());
            }
        }
        if (evidence.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "a classification without evidence quotes is not accepted");
        }
        // Tvilling T-P2: the frontier read the TWIN, so its quotes arrive in
        // twin-space. Re-anchor each one through the vault's offset map into
        // stored-text space BEFORE the verbatim gate — a quote that cuts a
        // surrogate in half cannot re-anchor and drops the classification.
        if ("twin".equals(dto.evidenceSpace())) {
            String twinText = signal.getTwinText();
            List<TwinningService.TwinSpan> spans = twinSpansOf(signalId, tenant);
            if (twinText == null || spans == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "twin evidence offered but the signal has no twin/vault");
            }
            Map<String, String> anchored = new LinkedHashMap<>();
            for (Map.Entry<String, String> q : evidence.entrySet()) {
                if (q.getValue() == null) {
                    continue;
                }
                String real = reanchor(q.getValue(), twinText, spans);
                if (real == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "evidence for '" + q.getKey() + "' cannot be re-anchored from the twin");
                }
                anchored.put(q.getKey(), real);
            }
            evidence = anchored;
        }
        for (Map.Entry<String, String> quote : evidence.entrySet()) {
            if (quote.getValue() == null) {
                continue;
            }
            if (!signal.getText().contains(quote.getValue())) {
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
        c.setSentiment(dto.sentiment());
        c.setAspect(dto.aspect());
        c.setCategory(dto.category());
        c.setPainPoint(dto.painPoint());
        c.setPainImpact(dto.painImpact());
        c.setLoyaltyIndicator(dto.loyaltyIndicator());
        c.setChurnSignal(Boolean.TRUE.equals(dto.churnSignal()));
        c.setChurnReason(dto.churnReason());
        c.setEvidence(json(evidence));
        c.setProvider(dto.provider());
        c.setModel(dto.model());
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

    private List<TwinningService.TwinSpan> twinSpansOf(String signalId, String tenant) {
        return twins.findByTenantIdAndSignalId(tenant, signalId).map(v -> {
            try {
                return objectMapper.readValue(v.getOffsetMap(),
                        new TypeReference<List<TwinningService.TwinSpan>>() { });
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    /** Translate a twin-space quote into stored-text space: shared segments
     * copy verbatim (identical in both), a WHOLLY covered surrogate becomes
     * its [TYPE] token, and a PARTIALLY cut surrogate is unmappable — null. */
    static String reanchor(String quote, String twinText, List<TwinningService.TwinSpan> spans) {
        int qs = twinText.indexOf(quote);
        if (qs < 0) {
            return null; // not verbatim from the twin — nothing to anchor
        }
        int qe = qs + quote.length();
        StringBuilder out = new StringBuilder();
        int pos = qs;
        for (TwinningService.TwinSpan span : spans) {
            if (span.twinEnd() <= qs || span.twinStart() >= qe) {
                continue;
            }
            if (span.twinStart() < qs || span.twinEnd() > qe) {
                return null; // the quote slices a surrogate — unmappable
            }
            out.append(twinText, pos, span.twinStart());
            out.append("[").append(span.type()).append("]");
            pos = span.twinEnd();
        }
        out.append(twinText, pos, qe);
        return out.toString();
    }

    /**
     * T-P4: the flywheel's dataset — every evidence-verified classification
     * as a fine-tune pair, IN TWIN SPACE: the input is the fiction, and the
     * stored-space evidence is reverse-anchored back through the offset map
     * ([TYPE] tokens become the twin's surrogates). The result is a training
     * corpus with ZERO real facts by construction — a local model learns the
     * taxonomy and the quoting contract without ever reading a customer.
     */
    @Transactional(readOnly = true)
    public List<FlywheelPair> flywheelDataset() {
        String tenant = tenantScope.currentTenantId();
        List<FlywheelPair> pairs = new java.util.ArrayList<>();
        for (CustomerSignal s : signals.findTop100ByTenantIdOrderByReceivedAtDesc(tenant)) {
            if (s.getTwinText() == null) {
                continue;
            }
            SignalClassification c = classifications
                    .findByTenantIdAndSignalId(tenant, s.getId()).orElse(null);
            if (c == null) {
                continue;
            }
            List<TwinningService.TwinSpan> spans = twinSpansOf(s.getId(), tenant);
            SignalClassificationView view = classificationView(c);
            // reverse-anchor the evidence into twin space; a quote that cannot
            // be mapped drops the PAIR — the corpus stays fiction-only
            JsonNode evidence = view.evidence();
            if (evidence != null && evidence.isObject() && spans != null) {
                ObjectNode twinEv = objectMapper.createObjectNode();
                boolean ok = true;
                for (Map.Entry<String, JsonNode> q : (Iterable<Map.Entry<String, JsonNode>>) evidence::fields) {
                    String mapped = toTwinSpace(q.getValue().isValueNode() ? q.getValue().asText() : q.getValue().toString(),
                            s.getText(), s.getTwinText(), spans);
                    if (mapped == null) {
                        ok = false;
                        break;
                    }
                    twinEv.put(q.getKey(), mapped);
                }
                if (!ok) {
                    continue;
                }
                evidence = twinEv;
            }
            pairs.add(new FlywheelPair(s.getTwinText(), s.getSource(), s.getLang(), view.label(evidence)));
        }
        return pairs;
    }

    /** The inverse of {@link #reanchor}: stored-space quote → twin-space
     * (a [TYPE] token becomes the twin's surrogate at that position). */
    static String toTwinSpace(String quote, String storedText, String twinText,
            List<TwinningService.TwinSpan> spans) {
        int qs = storedText.indexOf(quote);
        if (qs < 0) {
            return null;
        }
        int qe = qs + quote.length();
        StringBuilder out = new StringBuilder();
        int pos = qs;
        for (TwinningService.TwinSpan span : spans) {
            if (span.redactedEnd() <= qs || span.redactedStart() >= qe) {
                continue;
            }
            if (span.redactedStart() < qs || span.redactedEnd() > qe) {
                return null; // slices a token — unmappable
            }
            out.append(storedText, pos, span.redactedStart());
            out.append(twinText, span.twinStart(), span.twinEnd());
            pos = span.redactedEnd();
        }
        out.append(storedText, pos, qe);
        return out.toString();
    }

    private SignalClassificationView classificationView(SignalClassification c) {
        return new SignalClassificationView(c.getSentiment(), c.getAspect(), c.getCategory(), c.getPainPoint(),
                c.getPainImpact(), c.getLoyaltyIndicator(), c.isChurnSignal(), c.getChurnReason(),
                c.getEvidence() == null ? null : read(c.getEvidence()), c.getProvider(), c.getModel(),
                c.getClassifiedAt(), "SignalClassification");
    }

    @Transactional(readOnly = true)
    public SignalTwin twinOf(String signalId) {
        String tenant = tenantScope.currentTenantId();
        CustomerSignal s = signals.findById(signalId)
                .filter(x -> tenant.equals(x.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no signal '" + signalId + "'"));
        return new SignalTwin(signalId, s.getText(), s.getTwinText(),
                twins.findByTenantIdAndSignalId(tenant, signalId).isPresent(), "SignalTwin");
    }

    private SignalView view(CustomerSignal s, boolean duplicate) {
        return new SignalView(s.getId(), s.getSource(), s.getSourceRef(), s.getPartyId(), s.getChannel(), s.getLang(),
                s.getText(), s.getTwinText(), s.getContext() == null ? null : read(s.getContext()),
                s.getRedactions() == null ? null : read(s.getRedactions()), s.getReceivedAt(),
                duplicate ? Boolean.TRUE : null, "CustomerSignal", null);
    }

    private String json(Object v) {
        if (v == null || (v instanceof JsonNode n && n.isNull())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context is not serialisable");
        }
    }

    /** A stored JSON block as a tree; text that is not JSON comes back as the text it is. */
    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return TextNode.valueOf(json);
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
}
