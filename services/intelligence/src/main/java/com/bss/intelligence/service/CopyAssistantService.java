package com.bss.intelligence.service;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final AiAuditRepository audits;
    private final TenantScope tenantScope;

    public CopyAssistantService(LlmAdapter llm, Redactor redactor,
            AiAuditRepository audits, TenantScope tenantScope) {
        this.llm = llm;
        this.redactor = redactor;
        this.audits = audits;
        this.tenantScope = tenantScope;
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
                    + "it will be replaced with a promotion code.\n");
        }

        // Small models drift from the contract; one corrective retry fixes
        // most of it, and EVERY attempt lands in the ledger — failed calls
        // are the ones an operator most wants to see.
        String raw = llm.complete(system, user.toString());
        String subject = lineAfter(raw, "SUBJECT:");
        String body = lineAfter(raw, "BODY:");
        if (subject == null || body == null) {
            recordAudit(raw, user.toString(), system, "contract-miss");
            raw = llm.complete(system, user
                    + "\nYour previous answer did not follow the format. Respond again with"
                    + " ONLY the two lines, starting with 'SUBJECT:' and 'BODY:'.");
            subject = lineAfter(raw, "SUBJECT:");
            body = lineAfter(raw, "BODY:");
        }
        if (subject == null || body == null) {
            recordAudit(raw, user.toString(), system, "contract-miss");
            throw new BadRequestException("the model did not follow the SUBJECT/BODY contract");
        }
        // The engine substitutes {code}; a promo campaign whose draft lost the
        // placeholder would silently never deliver the reward.
        if (hasPromo && !body.contains("{code}")) {
            body = body + " Use code {code}.";
        }

        recordAudit(raw, user.toString(), system, "campaign-copy");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", subject);
        result.put("content", body);
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    private void recordAudit(String raw, String user, String system, String useCase) {
        AiAudit audit = new AiAudit();
        audit.setId(UUID.randomUUID().toString());
        audit.setTenantId(tenantScope.currentTenantId());
        audit.setUseCase(useCase);
        audit.setProvider(llm.provider());
        audit.setModel(llm.model());
        audit.setPrompt(truncate(system + "\n---\n" + user));
        audit.setResponse(truncate(raw));
        audit.setCreatedAt(OffsetDateTime.now());
        audits.save(audit);
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

    private static String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
