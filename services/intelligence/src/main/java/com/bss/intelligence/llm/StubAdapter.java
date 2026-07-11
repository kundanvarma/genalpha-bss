package com.bss.intelligence.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic no-network provider: the compose stack, the E2E suites and
 * air-gapped demos all work with zero API keys. It answers the copy-assistant
 * prompt contract (SUBJECT/BODY lines) from the brief itself, so the feature
 * is demonstrably wired end-to-end even before an operator picks a model.
 */
@Component
@ConditionalOnProperty(name = "bss.intelligence.provider", havingValue = "stub", matchIfMissing = true)
public class StubAdapter implements LlmAdapter {

    @Override
    public String complete(String system, String user) {
        String brief = user.replaceAll("(?s).*Brief:\\s*", "").replaceAll("(?s)\\n.*", "").trim();
        String subject = brief.isEmpty() ? "A little something for you"
                : Character.toUpperCase(brief.charAt(0)) + brief.substring(1);
        boolean promo = user.contains("promotion code");
        String body = promo
                ? "Hi! " + subject + " — use code {code} on your next order. It is our way of saying thanks."
                : "Hi! " + subject + " — thanks for being with us.";
        return "SUBJECT: " + subject + "\nBODY: " + body;
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String model() {
        return "deterministic-template";
    }
}
