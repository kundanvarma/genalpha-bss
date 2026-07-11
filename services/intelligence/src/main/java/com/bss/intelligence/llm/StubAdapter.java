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
        if (system.contains("SUMMARY:")) {
            return "SUMMARY: The provided customer data is summarized deterministically (stub"
                    + " provider — configure a real model for genuine insight). Nothing here"
                    + " left the machine.\n"
                    + "NEXT: Review the open items in the 360 with the customer.\n"
                    + "NEXT: Check whether the current plan still fits their usage.";
        }
        if (system.contains("NARRATIVE:")) {
            return "NARRATIVE: This quote packages guaranteed venue connectivity with"
                    + " edge AI inference next to the crowd. AI usage is token-metered:"
                    + " an included allowance, then a fixed price per million tokens —"
                    + " costs scale with the event, not ahead of it. (Stub provider.)";
        }
        if (system.contains("REPLY:")) {
            return "REPLY: Hi! Thanks for reaching out — we are looking into your case and"
                    + " will keep you posted here. (Drafted by the stub provider; configure"
                    + " a real model for tailored replies.)";
        }
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
