package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First AI feature: draft a campaign message from a one-line brief. The
 * model proposes; the marketer edits and saves — nothing goes to customers
 * without a human clicking Create. Output contract is two labeled lines
 * (SUBJECT/BODY) parsed here, so any provider that can follow one
 * instruction works, and the {code} placeholder survives templating.
 */
@Service
public class CopyAssistantService {

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final com.bss.intelligence.llm.AiGovernor governor;

    public CopyAssistantService(LlmAdapter llm, Redactor redactor,
            com.bss.intelligence.llm.AiGovernor governor) {
        this.llm = llm;
        this.redactor = redactor;
        this.governor = governor;
    }

    @Transactional
    public Map<String, Object> draftCampaignCopy(Map<String, Object> request) {
        if (request.get("brief") == null || String.valueOf(request.get("brief")).isBlank()) {
            throw new BadRequestException("brief is required");
        }
        String brief = redactor.redact(String.valueOf(request.get("brief")));
        String brandName = request.get("brandName") == null ? "the operator"
                : redactor.redact(String.valueOf(request.get("brandName")));
        String trigger = request.get("triggerEventType") == null ? null
                : String.valueOf(request.get("triggerEventType"));
        boolean hasPromo = request.get("promotionCode") != null
                && !String.valueOf(request.get("promotionCode")).isBlank();

        String system = "You write short, warm marketing messages for " + brandName
                + ", a telecom brand. Respond with ONLY two lines and nothing else, exactly:\n"
                + "SUBJECT: <subject, max 60 characters>\n"
                + "BODY: <body, max 300 characters, no emojis>\n"
                + "Example response:\n"
                + "SUBJECT: Welcome to the family\n"
                + "BODY: Hi! Thanks for joining us. We are glad you are here.";
        StringBuilder user = new StringBuilder("Brief: ").append(brief).append('\n');
        if (trigger != null) {
            user.append("The message is sent when this happens: ").append(trigger).append('\n');
        }
        if (hasPromo) {
            user.append("Include the literal placeholder {code} exactly once — "
                    + "it will be replaced with a promotion code. Do NOT state any discount"
                    + " amount or percentage; you do not know what the code is worth.\n");
        }

        // Small models drift from the contract; one corrective retry fixes
        // most of it. Every attempt rides the governor — metered, budgeted
        // and audited in one place (a retry lands as its own ledger row).
        String raw = governor.complete("campaign-copy",
                com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user.toString());
        String subject = lineAfter(raw, "SUBJECT:");
        String body = lineAfter(raw, "BODY:");
        if (subject == null || body == null) {
            raw = governor.complete("campaign-copy-retry",
                    com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user
                    + "\nYour previous answer did not follow the format. Respond again with"
                    + " ONLY the two lines, starting with 'SUBJECT:' and 'BODY:'.");
            subject = lineAfter(raw, "SUBJECT:");
            body = lineAfter(raw, "BODY:");
        }
        if (subject == null || body == null) {
            throw new BadRequestException("the model did not follow the SUBJECT/BODY contract");
        }
        // The engine substitutes {code}; a promo campaign whose draft lost the
        // placeholder would silently never deliver the reward.
        if (hasPromo && !body.contains("{code}")) {
            body = body + " Use code {code}.";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", subject);
        result.put("content", body);
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    /** Tolerant of markdown-happy models: "**SUBJECT:** hi" still parses. */
    private static String lineAfter(String raw, String label) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim().replaceFirst("^[*#>\\-\\s]+", "");
            if (trimmed.regionMatches(true, 0, label, 0, label.length())) {
                String value = trimmed.substring(label.length())
                        .replaceAll("^[*\\s]+|[*\\s]+$", "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

}
