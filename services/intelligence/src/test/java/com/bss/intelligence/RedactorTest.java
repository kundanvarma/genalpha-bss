package com.bss.intelligence;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** What the redactor recognises, how placeholders stay stable, and that the map reverses. */
class RedactorTest {

    private final Redactor redactor = new Redactor();

    @Test
    void masksEveryKnownTypeWithATypedPlaceholder() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Customer anna.svensson@example.com, phone +46 70 123 45 67, again +46 70 123 45 67.
                IBAN NO93 8601 1117 947 and account 8601.11.17947.
                ICCID 8947000000000000001, IMEI 490154203237518, card 4539 1488 0343 6467.
                fnr 01019912345, se 850101-1234, id 123456789.
                Address: Storgata 12, 0155 Oslo
                Renewal 2026-09-22 at 12:30, used 42 of 50 GB.""", map);
        assertThat(out)
                .contains("<email#1>").doesNotContain("anna.svensson")
                .contains("<phone#1>, again <phone#1>").doesNotContain("123 45 67")
                .contains("IBAN <iban#1>").doesNotContain("8601 1117 947")
                .contains("account <account#1>").doesNotContain("8601.11.17947")
                .contains("ICCID <iccid#1>").doesNotContain("8947000000000000001")
                .contains("IMEI <imei#1>").doesNotContain("490154203237518")
                .contains("card <card#1>").doesNotContain("4539 1488 0343 6467")
                .contains("fnr <nid#1>").doesNotContain("01019912345")
                .contains("se <nid#2>").doesNotContain("850101-1234")
                .contains("id <id#1>").doesNotContain("123456789")
                .contains("Address: <address#1>").doesNotContain("Storgata")
                // clocks and small numbers are not people
                .contains("2026-09-22 at 12:30, used 42 of 50 GB");
        assertThat(map.count()).isEqualTo(11);
    }

    @Test
    void aNumberThatFailsLuhnIsNotACard() {
        Redaction map = redactor.begin();
        String out = redactor.redact("ref 4539 1488 0343 6468", map);
        assertThat(map.placeholders()).noneMatch(p -> p.startsWith("<card"));
        assertThat(out).doesNotContain("<card");
    }

    @Test
    void restoreReversesTheMapEvenWhenTheModelDropsTheBrackets() {
        Redaction map = redactor.begin();
        redactor.redact("write to anna@example.com or call +47 912 34 567", map);
        String answer = "Sure — I will email <email#1> and, if needed, ring email#1's number &lt;phone#1&gt;.";
        assertThat(map.restore(answer)).isEqualTo(
                "Sure — I will email anna@example.com and, if needed, ring anna@example.com's number +47 912 34 567.");
    }

    @Test
    void theStatelessFormStillWorksForCallSites() {
        assertThat(redactor.redact("mail bob@example.org")).isEqualTo("mail <email#1>");
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void masksLabelledPersonNamesInProseAndJson() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Customer: Mira Nilsen
                Contact person: Olav Fjordbygg, subscriber: Mira Nilsen
                {"givenName": "Mira", "familyName": "Nilsen", "productName": "Fiber 1000"}
                Kunde: Nils Hansen; fornavn: Nils""", map);
        assertThat(out)
                // the same person keeps the SAME placeholder wherever they recur
                .contains("Customer: <name#3>")
                .contains("subscriber: <name#3>")
                .contains("Contact person: <name#4>")
                .doesNotContain("Mira Nilsen").doesNotContain("Olav Fjordbygg")
                .doesNotContain("Nils Hansen")
                // JSON stays valid JSON: label intact, only the value replaced
                .contains("\"givenName\": \"<name#1>\"")
                .contains("\"familyName\": \"<name#2>\"")
                // a product is not a person, even inside the same object
                .contains("\"productName\": \"Fiber 1000\"");
        assertThat(map.restore(out)).contains("Mira Nilsen").contains("Olav Fjordbygg");
    }

    @Test
    void doesNotEatThingsThatAreNotPeople() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Product name: Fiber 1000
                Campaign name: Winter Sale
                Plan name: GenAlpha Mobile 50 GB
                {"productName": "Galaxy S26", "offeringName": "TV Max"}
                The customer asked about pricing and the contact was by phone.""", map);
        assertThat(out)
                .contains("Product name: Fiber 1000")
                .contains("Campaign name: Winter Sale")
                .contains("Plan name: GenAlpha Mobile 50 GB")
                .contains("\"productName\": \"Galaxy S26\"")
                .contains("\"offeringName\": \"TV Max\"")
                // prose that merely mentions the words is not a labelled name
                .contains("The customer asked about pricing");
        assertThat(map.count()).isZero();
    }

    /**
     * A PROMPT IS UNTRUSTED TEXT, AND IT IS BIG. The labelled-address pattern used to
     * end `([^\\r\\n]+?)[ \\t]*$` — a lazy group whose character class also matches
     * space and tab, followed by a run of spaces and tabs. The two overlap, so the
     * engine retried the tail at every position. Measured on the real redactor before
     * the fix:
     *
     *     25 KB of trailing spaces -> 1.3 s,  51 KB -> 5.4 s,  100 KB -> 20.5 s
     *
     * One request thread, in the code that runs on every prompt, from input a caller
     * supplies. After the fix the same inputs take single-digit milliseconds.
     *
     * The bound here is deliberately loose. It is not measuring performance; it is
     * asking whether the cost is still linear, and two seconds is far below the
     * twenty this used to take while being far above any honest linear run.
     */
    @Test
    void aHugeAdversarialPromptStaysLinear() {
        String hostile = "Address: " + "a".repeat(10) + " ".repeat(102_400) + "!";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            Redaction map = redactor.begin();
            redactor.redact(hostile, map);
        }, "redaction of a 100 KB prompt took longer than 2s — the quadratic backtracking is back");
    }

    /**
     * The SAME shape in the email recogniser, found by the same CodeQL rule once the
     * address line stopped shouting. EMAIL was {@code [\w.+-]+@...}: an unbounded
     * greedy run with no word boundary in front of it. On text that never reaches an
     * {@code @} the engine consumed the run, failed, and retried from the next
     * character -- the whole run again, from every position:
     *
     *     10 KB of '+' -> 162 ms,  40 KB -> 2.4 s,  80 KB -> 10.8 s
     *
     * A prompt is caller-supplied text of exactly that size, so this was a request
     * thread anyone could hold for ten seconds. Bounded to RFC 5321's lengths the
     * same 80 KB costs 7 ms.
     */
    @Test
    void aPromptOfPunctuationDoesNotStallTheEmailRecogniser() {
        String hostile = "+".repeat(81_920);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            Redaction map = redactor.begin();
            redactor.redact(hostile, map);
        }, "redaction of an 80 KB run of '+' took longer than 2s — EMAIL is backtracking again");
    }

    /** Bounding the lengths must not change which addresses are recognised. */
    @Test
    void boundingTheEmailPatternStillRedactsARealAddress() {
        Redaction map = redactor.begin();
        String out = redactor.redact("Write to paula.nordmann+shop@family.example today.", map);

        assertThat(out).isEqualTo("Write to <email#1> today.").doesNotContain("nordmann");
        assertThat(map.restore(out)).isEqualTo("Write to paula.nordmann+shop@family.example today.");
    }

    /** The fix must not change WHAT is redacted: same input, same placeholder, value still gone. */
    @Test
    void trailingWhitespaceDoesNotChangeWhatAnAddressRedactsTo() {
        Redaction map = redactor.begin();
        String out = redactor.redact("Address: Storgata 12, 0155 Oslo      ", map);
        assertThat(out).isEqualTo("Address: <address#1>").doesNotContain("Storgata");
        assertThat(map.restore(out)).isEqualTo("Address: Storgata 12, 0155 Oslo");
    }
}
