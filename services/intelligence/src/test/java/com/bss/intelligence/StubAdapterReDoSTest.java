package com.bss.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bss.intelligence.llm.StubAdapter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A LONG PROMPT MUST NOT COST MINUTES OF CPU.
 *
 * The stub adapter used to pull the campaign brief and the price out of the
 * prompt with regexes shaped `.*Brief:\s*` and `.*?(\d+[.,]\d{2}).*` — patterns
 * that ask the engine to match the whole prompt and then backtrack to find the
 * interesting part. Measured on this laptop, against a prompt of repeated
 * digits with no price token in it:
 *
 *     2 KB      4,545 ms
 *     4 KB     37,825 ms
 *     8 KB    304,386 ms     (five minutes of one thread)
 *     400 KB   never finished
 *
 * The prompt is caller-supplied and the stub is the DEFAULT adapter
 * (`matchIfMissing = true`), so a 128 KB message to a fresh deployment with no
 * model configured is a denial of service that costs the attacker nothing.
 *
 * The bound below is deliberately loose. It is not a benchmark; it is the
 * difference between linear and catastrophic, and a loose bound is one that
 * will not flake on a busy CI box while still failing by four orders of
 * magnitude if someone reintroduces the backtracking shape.
 */
class StubAdapterReDoSTest {

    private static String digits(int kb) {
        StringBuilder b = new StringBuilder();
        while (b.length() < kb * 1024) {
            b.append("1234567890");
        }
        return b.toString();
    }

    /** The system prompt that routes into productCopilot — where the catastrophic
     * pattern lived. An earlier version of this test used a system prompt that
     * matched none of the branches, fell through to the (merely linear) brief
     * extraction, and passed against the UNFIXED code in 84 ms. A gate that
     * cannot fail is not a gate, so the routing is pinned here deliberately. */
    private static final String PRODUCT_COPILOT = "you are the product copilot for a telecom BSS";

    @Test
    @DisplayName("a 12128 KB streaming prompt with no price token still answers promptly")
    void aLongPromptDoesNotHangTheThread() {
        StubAdapter adapter = new StubAdapter();
        // "monthly" matters: without it the copilot returns at the earlier "what should\n        // it cost?" branch and never reaches the price parse at all. Reaches\n        // `asksStreaming && !asksWatch` with saidMonthly true, which is where the price was pulled
        // out with `.*?(\d+[.,]\d{2}).*` — 304 seconds on this input before the fix
        String hostile = "owner: add a streaming service monthly " + digits(128);

        Instant started = Instant.now();
        String reply = adapter.complete(PRODUCT_COPILOT, hostile);
        Duration took = Duration.between(started, Instant.now());

        assertThat(reply).isNotNull();
        assertThat(took)
                .as("the old regex took 304 seconds on this input; anything near that is the "
                        + "backtracking shape coming back")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the brief extraction is linear too, and a real price still parses")
    void theBriefPathIsSafeAndThePriceStillParses() {
        StubAdapter adapter = new StubAdapter();

        Instant started = Instant.now();
        adapter.complete("you are a marketing copy assistant", "Brief: " + digits(128));
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));

        // the fix must not have cost the behaviour it was protecting
        String priced = adapter.complete(PRODUCT_COPILOT, "owner: add a streaming service at 12,50 per month");
        assertThat(priced).contains("12.50");
    }
}
