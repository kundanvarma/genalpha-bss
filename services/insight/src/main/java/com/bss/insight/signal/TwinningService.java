package com.bss.insight.signal;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Tvilling engine (T-P1): ONE deterministic pass over a signal produces
 * BOTH protections — the redacted text the store keeps ([FNR] tokens, as
 * before) and the TWIN, the same narrative about a person who does not
 * exist: every recognized span swapped for a same-shaped surrogate drawn by
 * HMAC(signalKey, value) from fixed pools. Consistency inside a signal (the
 * same value → the same surrogate) and DIFFERENCE across signals (per-signal
 * key → the same customer is a different fiction every time — no
 * longitudinal profile even of the twin). The offset map ties twin-spans to
 * redacted-spans so frontier evidence quotes re-anchor exactly (T-P2). No
 * model, no cloud, no cost: string surgery you can audit character by
 * character.
 */
@Service
public class TwinningService {

    public record TwinSpan(int redactedStart, int redactedEnd, int twinStart, int twinEnd, String type) { }

    public record Twinned(String redacted, Map<String, Integer> counts,
                          String twin, List<TwinSpan> offsetMap) { }

    private static final String[] FIRST = {"Håkon", "Petter", "Sindre", "Vegard", "Trygve",
        "Aslak", "Brage", "Eivind", "Frode", "Gaute", "Ingebjørg", "Solfrid", "Tordis",
        "Oddveig", "Gunnhild", "Ragna", "Sunniva", "Torhild", "Åse", "Magnhild"};
    private static final String[] LAST = {"Lie", "Berge", "Holm", "Strand", "Vik", "Haugen",
        "Dahl", "Foss", "Lund", "Moen", "Nes", "Rud", "Sande", "Tangen", "Voll", "Ås"};

    private final List<PiiRecognizer> recognizers;

    public TwinningService(List<PiiRecognizer> recognizers) {
        this.recognizers = recognizers.stream()
                .sorted(Comparator.comparingInt(PiiRecognizer::order)).toList();
    }

    public Twinned twin(String raw, String signalKey) {
        // collect spans from every recognizer; earlier order wins overlaps
        record Found(PiiRecognizer.Span span, String type, int order) { }
        List<Found> all = new ArrayList<>();
        for (PiiRecognizer r : recognizers) {
            for (PiiRecognizer.Span s : r.find(raw)) {
                all.add(new Found(s, r.type(), r.order()));
            }
        }
        all.sort(Comparator.comparingInt((Found f) -> f.span().start())
                .thenComparingInt(Found::order));
        List<Found> kept = new ArrayList<>();
        int covered = -1;
        for (Found f : all) {
            if (f.span().start() > covered) {
                kept.add(f);
                covered = f.span().end() - 1;
            }
        }

        StringBuilder redacted = new StringBuilder();
        StringBuilder twin = new StringBuilder();
        List<TwinSpan> map = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int cursor = 0;
        for (Found f : kept) {
            String between = raw.substring(cursor, f.span().start());
            redacted.append(between);
            twin.append(between);
            String token = "[" + f.type() + "]";
            String surrogate = surrogate(f.type(), f.span().value(), signalKey);
            int r0 = redacted.length();
            int t0 = twin.length();
            redacted.append(token);
            twin.append(surrogate);
            map.add(new TwinSpan(r0, redacted.length(), t0, twin.length(), f.type()));
            counts.merge(f.type(), 1, Integer::sum);
            cursor = f.span().end();
        }
        String tail = raw.substring(cursor);
        redacted.append(tail);
        twin.append(tail);
        return new Twinned(redacted.toString(), counts, twin.toString(), map);
    }

    /** Same-shaped fiction, deterministic per (signalKey, value). */
    private String surrogate(String type, String value, String signalKey) {
        byte[] h = hmac(signalKey, type + "|" + value);
        return switch (type) {
            case "NAME" -> {
                String first = FIRST[idx(h, 0, FIRST.length)];
                yield value.trim().contains(" ")
                        ? first + " " + LAST[idx(h, 1, LAST.length)] : first;
            }
            case "PHONE" -> "+47 4" + digits(h, 2, 1) + " " + digits(h, 3, 2)
                    + " " + digits(h, 5, 2) + " " + digits(h, 7, 2);
            case "EMAIL" -> (FIRST[idx(h, 0, FIRST.length)] + "."
                    + LAST[idx(h, 1, LAST.length)] + "@example.net").toLowerCase(java.util.Locale.ROOT);
            case "FNR" -> String.format("%02d%02d%02d %s",
                    1 + idx(h, 2, 28), 1 + idx(h, 3, 12), 40 + idx(h, 4, 60), digits(h, 5, 5));
            case "CARD" -> digits(h, 2, 4) + " " + digits(h, 6, 4) + " "
                    + digits(h, 10, 4) + " " + digits(h, 14, 4);
            default -> "[" + type + "]"; // an unknown type degrades to the token
        };
    }

    private static int idx(byte[] h, int at, int mod) {
        return Math.floorMod(h[at], mod);
    }

    private static String digits(byte[] h, int at, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(Math.floorMod(h[(at + i) % h.length], 10));
        }
        return sb.toString();
    }

    private static byte[] hmac(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
