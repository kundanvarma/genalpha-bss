package com.bss.insight.signal;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The PII firewall (SI-P1). Runs every {@link PiiRecognizer} bean in order
 * and replaces matches with a type token ([FNR], [EMAIL], …). Returns the
 * redacted text plus the audit — which types, how many, NEVER the values.
 * Raw text must not outlive this call: callers store the result only.
 */
@Service
public class RedactionService {

    public record Redacted(String text, Map<String, Integer> counts) { }

    private final List<PiiRecognizer> recognizers;

    public RedactionService(List<PiiRecognizer> recognizers) {
        this.recognizers = recognizers.stream()
                .sorted(Comparator.comparingInt(PiiRecognizer::order)).toList();
    }

    public Redacted redact(String raw) {
        String text = raw;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PiiRecognizer r : recognizers) {
            Matcher m = r.pattern().matcher(text);
            int hits = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                hits++;
                m.appendReplacement(sb, "[" + r.type() + "]");
            }
            if (hits > 0) {
                m.appendTail(sb);
                text = sb.toString();
                counts.put(r.type(), hits);
            }
        }
        return new Redacted(text, counts);
    }
}
