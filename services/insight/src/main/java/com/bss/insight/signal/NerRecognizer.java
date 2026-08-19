package com.bss.insight.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The NER seam (Tvilling T-P1): a LOCAL model raises name/address recall
 * above the dictionary floor. Config-enabled (bss.insight.ner-url — a
 * spacy/GLiNER-class sidecar ON PREMISES; the whole point is that nothing
 * leaves), POST {text} → {entities:[{start,end,text,label}]}. Fail open:
 * an unreachable NER never blocks ingest — the deterministic floor still
 * ran, and the miss is a measured limit, not a silent lie.
 */
@Component
@ConditionalOnProperty("bss.insight.ner-url")
public class NerRecognizer implements PiiRecognizer {

    private static final Logger log = LoggerFactory.getLogger(NerRecognizer.class);

    private final RestClient client;

    public NerRecognizer(RestClient.Builder builder,
            @Value("${bss.insight.ner-url}") String nerUrl) {
        this.client = builder.baseUrl(nerUrl).build();
    }

    @Override
    public String type() {
        return "NAME";
    }

    @Override
    public Pattern pattern() {
        return null; // model recognizer — find() is the contract
    }

    @Override
    public int order() {
        return 60; // after the deterministic floor; overlaps dedupe upstream
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Span> find(String text) {
        List<Span> spans = new ArrayList<>();
        try {
            Map<String, Object> body = client.post().uri("/ner")
                    .header("Content-Type", "application/json")
                    .body(Map.of("text", text))
                    .retrieve().body(Map.class);
            List<Map<String, Object>> entities = body != null && body.get("entities") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            for (Map<String, Object> e : entities) {
                String label = String.valueOf(e.get("label"));
                if (!"PER".equals(label) && !"PERSON".equals(label) && !"LOC".equals(label)) {
                    continue;
                }
                int start = ((Number) e.get("start")).intValue();
                int end = ((Number) e.get("end")).intValue();
                spans.add(new Span(start, end, text.substring(start, end)));
            }
        } catch (Exception e) {
            log.warn("ner sidecar unreachable — deterministic floor only: {}", e.getMessage());
        }
        return spans;
    }
}
