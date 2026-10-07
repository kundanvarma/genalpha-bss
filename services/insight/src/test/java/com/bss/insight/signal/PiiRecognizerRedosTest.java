package com.bss.insight.signal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The PII firewall scans whatever a connector ingested, so the text these
 * patterns run over is not ours. The email recogniser used to be
 * {@code [A-Za-z0-9._%+-]+@...}: an unbounded greedy run with no word boundary
 * in front of it, so on text that never reaches an '@' the engine consumed the
 * run, failed, retried from the next character, and consumed it again -- from
 * every position.
 *
 * <pre>
 *   10 KB of letters -> 208 ms
 *   40 KB            -> 4.4 s
 *   80 KB            -> 18.4 s
 * </pre>
 *
 * CodeQL did not flag this one. It flagged the twin in
 * intelligence/Redactor.java, which was the shorter stall of the two at 10.8 s
 * -- so this is a case where the fix had to come from reading the pattern
 * rather than from the alert list, and the test is here so the next person does
 * not have to notice again.
 */
class PiiRecognizerRedosTest {

    private static final List<PiiRecognizer> ALL = List.of(
            new DeterministicRecognizers.Fnr(), new DeterministicRecognizers.Card(),
            new DeterministicRecognizers.Email(), new DeterministicRecognizers.Phone());

    /**
     * Deliberately loose. It is not measuring performance; it asks whether the
     * cost is still linear, and two seconds sits far below the eighteen this
     * took and far above any honest linear run.
     */
    @Test
    void noRecogniserStallsOnAHostilePayload() {
        for (PiiRecognizer r : ALL) {
            for (String hostile : List.of("a".repeat(81_920), "%".repeat(81_920),
                    "+".repeat(81_920), "1".repeat(81_920), ".".repeat(81_920))) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    Matcher m = r.pattern().matcher(hostile);
                    while (m.find()) {
                        // consume: find() is where the backtracking lived
                    }
                }, () -> "recogniser " + r.type() + " took longer than 2s on 80 KB of '"
                        + hostile.charAt(0) + "' — the quadratic backtracking is back");
            }
        }
    }

    /** Bounding the lengths must not change which addresses are recognised. */
    @Test
    void theEmailRecogniserStillFindsARealAddress() {
        Matcher m = new DeterministicRecognizers.Email().pattern()
                .matcher("reach anna.svensson+news@example.co.uk now");

        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("anna.svensson+news@example.co.uk");
    }

    @Test
    void theOtherRecognisersStillFindWhatTheyAreFor() {
        assertThat(find(new DeterministicRecognizers.Fnr(), "fnr 010199 12345 here"))
                .isEqualTo("010199 12345");
        assertThat(find(new DeterministicRecognizers.Card(), "card 4539 1488 0343 6467 here"))
                .isEqualTo("4539 1488 0343 6467");
        assertThat(find(new DeterministicRecognizers.Phone(), "ring 90 12 34 56 today"))
                .isEqualTo("90 12 34 56");
    }

    private static String find(PiiRecognizer r, String text) {
        Matcher m = r.pattern().matcher(text);
        return m.find() ? m.group() : null;
    }
}
