package com.bss.knowledge.embed;

/**
 * Where meaning becomes a vector. The stub is deterministic and keyless
 * (CI, air-gapped demos): hashed bag-of-words folded through a small
 * synonym-class table, so "slow internet" and "data throttling" land
 * near each other honestly. A real embeddings model plugs in behind the
 * same two methods (EMBEDDINGS_PROVIDER=openai-compatible) — the seam
 * pattern, once more.
 */
public interface EmbeddingProvider {

    float[] embed(String text);

    /** Cosine-distance ceiling: matches farther than this are noise. */
    default double ceiling() {
        return 0.55;
    }
}
