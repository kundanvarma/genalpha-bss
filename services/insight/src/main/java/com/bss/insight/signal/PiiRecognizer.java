package com.bss.insight.signal;

import java.util.regex.Pattern;

/**
 * One kind of PII the firewall removes at ingest. Beans implementing this are
 * auto-discovered into the {@link RedactionService} (the carrier-adapter
 * discovery idiom) — a model-based NER recognizer joins the same list later
 * (the Presidio-style layered doctrine: deterministic first, NER on top).
 */
public interface PiiRecognizer {

    /** The type label, used in the replacement token and the redaction audit. */
    String type();

    Pattern pattern();

    /** Lower runs first — digit-heavy types must run before looser ones. */
    int order();

    /** One matched stretch of PII in a text. */
    record Span(int start, int end, String value) { }

    /** The spans this recognizer finds. Regex recognizers get it for free;
     * a dictionary or NER recognizer overrides (pattern() may return null
     * for those — only find() is the contract the twin engine uses). */
    default java.util.List<Span> find(String text) {
        java.util.List<Span> spans = new java.util.ArrayList<>();
        java.util.regex.Matcher m = pattern().matcher(text);
        while (m.find()) {
            spans.add(new Span(m.start(), m.end(), m.group()));
        }
        return spans;
    }
}
