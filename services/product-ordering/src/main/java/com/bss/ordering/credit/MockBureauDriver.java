package com.bss.ordering.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic bureau stand-in: outcomes come from configuration
 * ({@code bss.credit.mock.outcomes}, "ref:decision" pairs — the default
 * ships one frozen test identity) plus the runtime overrides the test/admin
 * endpoint sets. Everything unlisted approves with a clean band — the mock
 * is a seam target, not a scoring model; a real bureau driver replaces this
 * bean per deployment, config only.
 */
@Component
public class MockBureauDriver implements CreditDecisionPort {

    private static final Logger log = LoggerFactory.getLogger(MockBureauDriver.class);

    private final Map<String, String> outcomes = new ConcurrentHashMap<>();

    public MockBureauDriver(
            @Value("${bss.credit.mock.outcomes:frozen-test-identity:frozen}") String seeded) {
        for (String pair : seeded.split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2 && !parts[0].isBlank()) {
                outcomes.put(parts[0].trim(), parts[1].trim());
            }
        }
    }

    /** Test/admin lever: pin a decision for a reference (null decision clears). */
    public void setOutcome(String ref, String decision) {
        if (decision == null || decision.isBlank()) {
            outcomes.remove(ref);
        } else {
            outcomes.put(ref, decision);
        }
    }

    @Override
    public Decision assess(String nationalIdRef, String purpose) {
        String decision = outcomes.getOrDefault(nationalIdRef, APPROVE);
        log.debug("mock bureau: {} -> {} ({})", nationalIdRef, decision, purpose);
        return switch (decision) {
            case FROZEN -> new Decision(FROZEN, null, false);
            case DECLINE -> new Decision(DECLINE, "E", true);
            case REVIEW -> new Decision(REVIEW, "C", true);
            default -> new Decision(APPROVE, "A", false);
        };
    }
}
