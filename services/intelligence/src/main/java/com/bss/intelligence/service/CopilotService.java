package com.bss.intelligence.service;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The CSR copilot: summarize a customer's 360 and draft ticket replies.
 * The console sends the data the agent can already see — the copilot never
 * fetches on its own credentials, so it can never show an agent more than
 * their token could. Drafts land in editable fields; the agent sends.
 */
@Service
public class CopilotService {

    private static final int CONTEXT_CHARS = 3000;

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final AiAuditRepository audits;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public CopilotService(LlmAdapter llm, Redactor redactor, AiAuditRepository audits,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.llm = llm;
        this.redactor = redactor;
        this.audits = audits;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> summarizeCustomer(Map<String, Object> request) {
        String context = contextOf(request);
        String system = "You are a telecom customer-service copilot. From the customer data,"
                + " tell the agent what is going on and what to do next. Respond with ONLY"
                + " these labeled lines and nothing else:\n"
                + "SUMMARY: <2-3 sentences: situation, anything unusual>\n"
                + "NEXT: <one concrete action>\n"
                + "NEXT: <another action (1 to 3 NEXT lines total)>";
        String user = "Customer data (JSON):\n" + context;

        String raw = completeWithRetry(system, user, "customer-summary");
        String summary = lineAfter(raw, "SUMMARY:");
        List<String> next = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String value = valueOf(line, "NEXT:");
            if (value != null) {
                next.add(value);
            }
        }
        if (summary == null || next.isEmpty()) {
            throw new BadRequestException("the model did not follow the SUMMARY/NEXT contract");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("nextActions", next.subList(0, Math.min(3, next.size())));
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    /**
     * The AI chat seam of the PoC: a B2B sales manager types what the customer
     * wants in plain language; the model returns a structured TMF921 intent
     * expression the OSS can run feasibility on. The sales manager confirms —
     * no swivel-chairing into a form.
     */
    @Transactional
    public Map<String, Object> draftIntent(Map<String, Object> request) {
        if (request.get("ask") == null || String.valueOf(request.get("ask")).isBlank()) {
            throw new BadRequestException("ask (the business need in plain language) is required");
        }
        String ask = redactor.redact(String.valueOf(request.get("ask")));
        String system = "You turn a telecom B2B sales ask into a network intent. Infer sensible"
                + " numbers from context (a stadium AI experience needs very low latency and high"
                + " bandwidth). Respond with ONLY these labeled lines:\n"
                + "NAME: <short intent name>\n"
                + "PLACE: <slug of the location, lowercase-hyphenated>\n"
                + "LATENCY_MS: <integer round-trip budget>\n"
                + "BANDWIDTH_MBPS: <integer>\n"
                + "AI_TOKENS_MILLIONS: <integer, or 0 if no AI workload>";
        String raw = completeWithRetry(system, "Ask: " + ask, "intent-draft");
        String place = lineAfter(raw, "PLACE:");
        String latency = lineAfter(raw, "LATENCY_MS:");
        if (place == null || latency == null) {
            throw new BadRequestException("the model did not return a usable intent expression");
        }
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("place", place);
        expression.put("latencyMs", digits(latency, 20));
        expression.put("bandwidthMbps", digits(lineAfter(raw, "BANDWIDTH_MBPS:"), 1000));
        long tokens = digits(lineAfter(raw, "AI_TOKENS_MILLIONS:"), 0);
        if (tokens > 0) {
            expression.put("aiTokensMillions", tokens);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", lineAfter(raw, "NAME:") == null ? "B2B network intent" : lineAfter(raw, "NAME:"));
        result.put("expression", expression);
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    private static long digits(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String num = value.replaceAll("[^0-9]", "");
        return num.isEmpty() ? fallback : Long.parseLong(num);
    }

    @Transactional
    public Map<String, Object> draftQuoteNarrative(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            throw new BadRequestException("a quote context payload is required");
        }
        String system = "You write a short executive summary for a telecom B2B quote."
                + " Plain language, no hype, mention what is included and how AI usage"
                + " is metered if token allowances appear. Respond with ONLY one labeled line:\n"
                + "NARRATIVE: <max 500 characters>";
        String user = "Quote (JSON):\n" + contextOf(request);
        String raw = completeWithRetry(system, user, "quote-narrative");
        String narrative = lineAfter(raw, "NARRATIVE:");
        if (narrative == null) {
            throw new BadRequestException("the model did not follow the NARRATIVE contract");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("narrative", narrative);
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    @Transactional
    public Map<String, Object> draftTicketReply(Map<String, Object> request) {
        if (!(request.get("ticket") instanceof Map<?, ?> ticket) || ticket.get("name") == null) {
            throw new BadRequestException("ticket {name, ...} is required");
        }
        String system = "You are a telecom customer-service copilot. Draft a short, empathetic"
                + " reply the agent can send to the customer about their support ticket."
                + " Do not promise refunds or deadlines. Respond with ONLY one labeled line:\n"
                + "REPLY: <the reply, max 400 characters>";
        String user = "Ticket (JSON):\n" + contextOf(request);

        String raw = completeWithRetry(system, user, "ticket-reply");
        String reply = lineAfter(raw, "REPLY:");
        if (reply == null) {
            throw new BadRequestException("the model did not follow the REPLY contract");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        return result;
    }

    /** Serialize, cap and redact whatever slice of the 360 the console sent. */
    private String contextOf(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            throw new BadRequestException("a context payload is required");
        }
        try {
            String json = objectMapper.writeValueAsString(request);
            if (json.length() > CONTEXT_CHARS) {
                json = json.substring(0, CONTEXT_CHARS);
            }
            return redactor.redact(json);
        } catch (Exception e) {
            throw new BadRequestException("context payload is not serializable");
        }
    }

    private String completeWithRetry(String system, String user, String useCase) {
        String raw = llm.complete(com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user);
        if (looksUnlabeled(raw)) {
            recordAudit(raw, user, system, useCase + "-contract-miss");
            raw = llm.complete(com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user
                    + "\nYour previous answer did not follow the format. Respond again using"
                    + " ONLY the labeled lines from the instructions.");
        }
        recordAudit(raw, user, system, useCase);
        return raw;
    }

    private static boolean looksUnlabeled(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim().replaceFirst("^[*#>\\-\\s]+", "");
            if (t.regionMatches(true, 0, "SUMMARY:", 0, 8)
                    || t.regionMatches(true, 0, "REPLY:", 0, 6)
                    || t.regionMatches(true, 0, "NARRATIVE:", 0, 10)
                    || t.regionMatches(true, 0, "PLACE:", 0, 6)) {
                return false;
            }
        }
        return true;
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

    private static String lineAfter(String raw, String label) {
        for (String line : raw.split("\\R")) {
            String value = valueOf(line, label);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Tolerant of markdown-happy models: "**NEXT:** call" still parses. */
    private static String valueOf(String line, String label) {
        String trimmed = line.trim().replaceFirst("^[*#>\\-\\s]+", "");
        if (trimmed.regionMatches(true, 0, label, 0, label.length())) {
            String value = trimmed.substring(label.length())
                    .replaceAll("^[*\\s]+|[*\\s]+$", "").trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
