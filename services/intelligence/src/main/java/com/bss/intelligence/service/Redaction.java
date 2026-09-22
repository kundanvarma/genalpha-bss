package com.bss.intelligence.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One model call's redaction map: every personal value the {@link Redactor}
 * replaced, and the placeholder it became. Placeholders are STABLE within
 * the call ({@code <email#1>} is the same address wherever it recurs) and
 * TYPED, so the model can still say "reply to <email#1>" — and
 * {@link #restore(String)} puts the real value back into the answer before
 * the caller sees it. Not thread-safe by design: one map per call.
 */
public final class Redaction {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("(?:<|&lt;)?\\b([a-z]+)#(\\d+)\\b(?:>|&gt;)?");

    private final Map<String, String> placeholderByValue = new LinkedHashMap<>();
    private final Map<String, String> valueByPlaceholder = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    /** The placeholder for this value — minted on first sight, reused after. */
    public String placeholder(String type, String value) {
        String existing = placeholderByValue.get(value);
        if (existing != null) {
            return existing;
        }
        int n = counters.merge(type, 1, Integer::sum);
        String placeholder = "<" + type + "#" + n + ">";
        placeholderByValue.put(value, placeholder);
        valueByPlaceholder.put(placeholder, value);
        return placeholder;
    }

    /** Put the real values back: the model's answer, un-redacted for the caller.
     * Tolerates a model that dropped the angle brackets or HTML-escaped them. */
    public String restore(String text) {
        if (text == null || valueByPlaceholder.isEmpty()) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String original = valueByPlaceholder.get("<" + m.group(1) + "#" + m.group(2) + ">");
            m.appendReplacement(out, Matcher.quoteReplacement(original == null ? m.group() : original));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** How many distinct personal values this call redacted. */
    public int count() {
        return valueByPlaceholder.size();
    }

    /** placeholder → original, in the order they were found. */
    public Map<String, String> entries() {
        return Collections.unmodifiableMap(valueByPlaceholder);
    }

    /** The placeholders minted so far, in order. */
    public List<String> placeholders() {
        return List.copyOf(valueByPlaceholder.keySet());
    }
}
