package com.bss.knowledge.embed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Deterministic, keyless, and honest about what it is: lexical semantics
 * via synonym classes + hashed tokens, normalized. Enough to prove the
 * pgvector plumbing end to end and to make the demo's semantic net
 * genuinely useful; a trained model makes it smarter, not different.
 */
@Component
@ConditionalOnProperty(name = "bss.knowledge.embeddings", havingValue = "stub", matchIfMissing = true)
public class StubEmbeddingProvider implements EmbeddingProvider {

    static final int DIM = 256;

    /** Small, curated classes — telecom's recurring meanings, EN + NO. */
    private static final Map<String, Integer> CLASSES = Map.ofEntries(
            Map.entry("slow", 1), Map.entry("slower", 1), Map.entry("speed", 1),
            Map.entry("speeds", 1), Map.entry("throttle", 1), Map.entry("throttling", 1),
            Map.entry("reduced", 1), Map.entry("treg", 1), Map.entry("hastighet", 1),
            Map.entry("bill", 2), Map.entry("bills", 2), Map.entry("invoice", 2),
            Map.entry("charge", 2), Map.entry("charges", 2), Map.entry("regning", 2),
            Map.entry("regninger", 2), Map.entry("regningene", 2), Map.entry("faktura", 2),
            Map.entry("internet", 3), Map.entry("broadband", 3), Map.entry("data", 3),
            Map.entry("connection", 3), Map.entry("wifi", 3), Map.entry("nett", 3),
            Map.entry("expensive", 4), Map.entry("price", 4), Map.entry("cost", 4),
            Map.entry("dyr", 4), Map.entry("pris", 4),
            Map.entry("cancel", 5), Map.entry("terminate", 5), Map.entry("leave", 5),
            Map.entry("quit", 5), Map.entry("oppsigelse", 5),
            Map.entry("roaming", 6), Map.entry("abroad", 6), Map.entry("travel", 6),
            Map.entry("utlandet", 6),
            Map.entry("sim", 7), Map.entry("esim", 7), Map.entry("puk", 7),
            Map.entry("pin", 7));

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        for (String raw : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2) {
                continue;
            }
            Integer cls = CLASSES.get(raw);
            if (cls != null) {
                // synonym classes get strong, shared dimensions — this is
                // where "slow" and "throttling" become neighbours
                v[cls] += 3.0f;
            }
            v[8 + Math.floorMod(raw.hashCode(), DIM - 8)] += 1.0f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) {
                v[i] *= scale;
            }
        }
        return v;
    }
}
