package com.bss.intelligence.llm;

/**
 * What a provider answered, and — when it said — how many tokens it counted.
 * The text is what every caller reads; the usage is what the meter reads.
 * A provider that reports no usage (the stub, a server that leaves the block
 * off) answers with {@code usage == null}, and the governor falls back to its
 * chars/4 estimate and says so on the ledger row.
 */
public record LlmCompletion(String text, Usage usage) {

    /** The provider's own counts: input includes any cached-prompt tokens it billed. */
    public record Usage(int inputTokens, int outputTokens) {
    }

    public static LlmCompletion textOnly(String text) {
        return new LlmCompletion(text, null);
    }
}
