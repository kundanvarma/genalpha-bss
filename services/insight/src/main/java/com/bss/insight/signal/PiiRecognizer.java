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
}
