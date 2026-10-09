package com.bss.ontology.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One intent, one key.
 *
 * Without an idempotency key the retry and timeout rules in the receipts
 * proposal are unimplementable: two receipts for one intent are
 * indistinguishable from two intents, and a reader cannot tell a retry from a
 * repeat. decisionId is already the per-ATTEMPT identity; this is the
 * per-INTENT one, and the pair is what makes retries countable.
 *
 * So the property under test is not "a key exists" but "the same intent
 * derives the same key, and a different intent cannot collide with it" — which
 * is why the key is a digest over the caller, action and inputs rather than a
 * random value, and why neither the timestamp nor decisionId is in it.
 */
class IdempotencyKeyTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Caller caller(String tenant, String subject) {
        return new Caller("bearer", subject, Set.of("staff"), "console", tenant, null);
    }

    private JsonNode action(String name, int version) {
        return json.readTree("{\"action\":\"" + name + "\",\"version\":" + version + "}");
    }

    /** The method is private by design; the behaviour is what matters. */
    private String key(Caller c, JsonNode a, Map<String, String> inputs) throws Exception {
        Method m = ActionExecuteService.class.getDeclaredMethod(
                "idempotencyKey", Caller.class, JsonNode.class, Map.class);
        m.setAccessible(true);
        return (String) m.invoke(null, c, a, inputs);
    }

    private Map<String, String> inputs(String... kv) {
        // The same off-the-end read I fixed in BridgeService.paths() this
        // morning (#247) and then wrote again here. An odd count is a typo in
        // a test, not bad input — but kv[i + 1] still runs past the end, and
        // the failure would be an ArrayIndexOutOfBounds in the setup rather
        // than the assertion anyone was reading.
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "inputs() takes key/value PAIRS; got " + kv.length + " values");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void theSameIntentTwiceIsTheSameKey() throws Exception {
        String a = key(caller("genalpha", "pat"), action("changePlan", 3), inputs("plan", "Mobile M"));
        String b = key(caller("genalpha", "pat"), action("changePlan", 3), inputs("plan", "Mobile M"));

        assertThat(a).isEqualTo(b).hasSize(32);
    }

    /**
     * A map's iteration order must not change the key, or a retry stops
     * matching the original it is retrying — the inputs are sorted for exactly
     * this reason.
     */
    @Test
    void inputOrderDoesNotChangeTheKey() throws Exception {
        String a = key(caller("genalpha", "pat"), action("changePlan", 3),
                inputs("plan", "Mobile M", "effective", "2026-11-01"));
        String b = key(caller("genalpha", "pat"), action("changePlan", 3),
                inputs("effective", "2026-11-01", "plan", "Mobile M"));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void adifferentIntentIsADifferentKey() throws Exception {
        String base = key(caller("genalpha", "pat"), action("changePlan", 3), inputs("plan", "Mobile M"));

        assertThat(key(caller("genalpha", "pat"), action("changePlan", 3), inputs("plan", "Mobile L")))
                .as("different inputs").isNotEqualTo(base);
        assertThat(key(caller("genalpha", "wilma"), action("changePlan", 3), inputs("plan", "Mobile M")))
                .as("different caller").isNotEqualTo(base);
        assertThat(key(caller("nova", "pat"), action("changePlan", 3), inputs("plan", "Mobile M")))
                .as("different tenant — one operator's key must never be another's")
                .isNotEqualTo(base);
        assertThat(key(caller("genalpha", "pat"), action("cancelPlan", 3), inputs("plan", "Mobile M")))
                .as("different action").isNotEqualTo(base);
        assertThat(key(caller("genalpha", "pat"), action("changePlan", 4), inputs("plan", "Mobile M")))
                .as("different action version — v4 is not the same contract as v3")
                .isNotEqualTo(base);
    }

    /**
     * The point of a derived key: it survives a restart and a second process.
     * A random value would satisfy "a key exists" and none of the rules that
     * need one.
     */
    @Test
    void theKeyIsDerivedAndNotRandom() throws Exception {
        Map<String, String> in = inputs("plan", "Mobile M");
        String first = key(caller("genalpha", "pat"), action("changePlan", 3), in);
        for (int i = 0; i < 5; i++) {
            assertThat(key(caller("genalpha", "pat"), action("changePlan", 3), in)).isEqualTo(first);
        }
        assertThat(first).matches("[0-9a-f]{32}");
    }
}
