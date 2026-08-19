package com.bss.intelligence.signal;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The classification battery (SI-P3). Pulls unclassified signals from the
 * store and classifies each on the FAST tier — the volume-work tier, which a
 * deployment points at a LOCAL model (openai-compatible → Ollama/vLLM) so
 * redacted customer text never leaves the premises; the frontier tier never
 * sees signal text. Every call runs through the {@link AiGovernor} (audit
 * ledger, budget). The battery quotes its evidence VERBATIM; quotes are
 * verified twice — here before the write, and again at the store, which
 * refuses a classification that cannot cite its source. Fail open: a parse
 * or verification failure drops THAT signal (it stays unclassified for the
 * next sweep), never the sweep.
 */
@Service
public class SignalClassifier {

    private static final Logger log = LoggerFactory.getLogger(SignalClassifier.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private static final String SYSTEM = """
            You classify ONE customer signal from a telecom operator (support tickets, reviews, \
            chats, call transcripts; Norwegian or English; PII already redacted to tokens like \
            [PHONE]). Reply with ONLY a JSON object — no fences, no prose, nothing after the \
            closing brace:
            {"sentiment":"positive|neutral|negative",
             "aspect":"product|billing|network|support|price|other",
             "category":"fault|feature-request|question|praise|complaint",
             "painPoint":"one short sentence, or null",
             "painImpact":1-5 or null,
             "loyaltyIndicator":"promoter|passive|detractor",
             "churnSignal":true|false,
             "churnReason":"one short sentence, or null",
             "evidence":{"sentiment":"<copied words>","category":"<copied words>","churnSignal":"<copied words>"}}
            THE EVIDENCE CONTRACT: each evidence value is a short phrase COPIED CHARACTER-FOR-\
            CHARACTER from the signal text — the exact words that justify that field. NEVER put \
            a label (like "negative" or "fault") in evidence; the store rejects any evidence \
            that is not an exact substring of the signal text.
            EXAMPLE signal: "Regningen er feil igjen, og nå bytter jeg leverandør."
            EXAMPLE evidence: {"sentiment":"Regningen er feil igjen","category":"Regningen er feil",\
            "churnSignal":"nå bytter jeg leverandør"}
            churnSignal=true only when the text itself signals leaving, cancelling or switching. \
            Do not invent facts absent from the text.""";

    private final BssApiClient bss;
    private final AiGovernor governor;
    private final com.bss.intelligence.llm.LlmAdapter llm;
    private final TenantRegistry tenants;
    private final ObjectMapper objectMapper;
    private final int batchCap;

    public SignalClassifier(BssApiClient bss, AiGovernor governor,
            com.bss.intelligence.llm.LlmAdapter llm, TenantRegistry tenants,
            ObjectMapper objectMapper,
            @Value("${bss.intelligence.signal.batch-cap:20}") int batchCap) {
        this.bss = bss;
        this.governor = governor;
        this.llm = llm;
        this.tenants = tenants;
        this.objectMapper = objectMapper;
        this.batchCap = batchCap;
    }

    @Scheduled(initialDelayString = "${bss.intelligence.signal.initial-delay-ms:120000}",
            fixedDelayString = "${bss.intelligence.signal.fixed-delay-ms:300000}")
    public void scheduledSweep() {
        sweepAllTenants();
    }

    public Map<String, Object> sweepAllTenants() {
        int classified = 0;
        int dropped = 0;
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                int[] result = sweepCurrentTenant();
                classified += result[0];
                dropped += result[1];
            } catch (Exception e) {
                log.warn("signal sweep skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
        return Map.of("classified", classified, "dropped", dropped);
    }

    /** @return {classified, dropped} for the current tenant. */
    public int[] sweepCurrentTenant() {
        int classified = 0;
        int dropped = 0;
        List<Map<String, Object>> pending = bss.unclassifiedSignals();
        for (Map<String, Object> signal : pending.subList(0, Math.min(pending.size(), batchCap))) {
            String id = String.valueOf(signal.get("id"));
            String text = String.valueOf(signal.get("text"));
            try {
                String user = "Source: " + signal.get("source")
                        + (signal.get("lang") != null ? " · language: " + signal.get("lang") : "")
                        + "\nSignal text:\n" + text;
                String answer = governor.complete("signal-classification",
                        LlmAdapter.Tier.FAST, SYSTEM, user);
                Map<String, Object> parsed = parse(answer);
                if (parsed == null || !quotesHold(parsed, text)) {
                    dropped++;
                    continue;
                }
                // who ACTUALLY spoke: the FAST tier's resolved adapter
                parsed.put("provider", llm.provider(LlmAdapter.Tier.FAST));
                parsed.put("model", llm.model(LlmAdapter.Tier.FAST));
                if (bss.postSignalClassification(id, parsed)) {
                    classified++;
                } else {
                    // the store's own verification said no — same drop, one count
                    dropped++;
                }
            } catch (Exception e) {
                log.warn("signal {} left unclassified: {}", id, e.getMessage());
                dropped++;
            }
        }
        return new int[] {classified, dropped};
    }

    private Map<String, Object> parse(String answer) {
        if (answer == null) {
            return null;
        }
        String body = answer.strip();
        if (body.startsWith("```")) {
            body = body.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(body.substring(start, end + 1), JSON);
        } catch (Exception e) {
            return null;
        }
    }

    /** The local half of the double verification — don't even post a
     * classification whose quotes aren't verbatim substrings. */
    @SuppressWarnings("unchecked")
    private boolean quotesHold(Map<String, Object> parsed, String text) {
        if (!(parsed.get("evidence") instanceof Map<?, ?> evRaw) || evRaw.isEmpty()) {
            return false;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : evRaw.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!text.contains(String.valueOf(e.getValue()))) {
                return false;
            }
            evidence.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (evidence.isEmpty()) {
            return false;
        }
        parsed.put("evidence", evidence);
        return true;
    }
}
