package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.service.CopilotRequests.IntentAsk;
import com.bss.intelligence.service.IntentDraft.IntentExpression;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    private final com.bss.intelligence.llm.AiGovernor governor;
    private final ObjectMapper objectMapper;

    public CopilotService(LlmAdapter llm,
            com.bss.intelligence.llm.AiGovernor governor, ObjectMapper objectMapper) {
        this.llm = llm;
        this.governor = governor;
        this.objectMapper = objectMapper;
    }

    /** @param request the slice of the 360 the console sent — an open document, serialised as is */
    @Transactional
    public CustomerSummary summarizeCustomer(JsonNode request) {
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
        return new CustomerSummary(summary, next.subList(0, Math.min(3, next.size())),
                llm.provider(), llm.model());
    }

    /**
     * The AI chat seam of the PoC: a B2B sales manager types what the customer
     * wants in plain language; the model returns a structured TMF921 intent
     * expression the OSS can run feasibility on. The sales manager confirms —
     * no swivel-chairing into a form.
     */
    @Transactional
    public IntentDraft draftIntent(IntentAsk request) {
        if (request.ask() == null || request.ask().isBlank()) {
            throw new BadRequestException("ask (the business need in plain language) is required");
        }
        // no call-site redaction: the governor redacts before send and
        // restores the real values in the answer
        String ask = request.ask();
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
        long tokens = digits(lineAfter(raw, "AI_TOKENS_MILLIONS:"), 0);
        IntentExpression expression = new IntentExpression(place, digits(latency, 20),
                digits(lineAfter(raw, "BANDWIDTH_MBPS:"), 1000), tokens > 0 ? tokens : null);
        return new IntentDraft(
                lineAfter(raw, "NAME:") == null ? "B2B network intent" : lineAfter(raw, "NAME:"),
                expression, llm.provider(), llm.model());
    }

    private static long digits(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String num = value.replaceAll("[^0-9]", "");
        return num.isEmpty() ? fallback : Long.parseLong(num);
    }

    @Transactional
    public QuoteNarrative draftQuoteNarrative(JsonNode request) {
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
        return new QuoteNarrative(narrative, llm.provider(), llm.model());
    }

    @Transactional
    public TicketReplyDraft draftTicketReply(JsonNode request) {
        JsonNode ticket = request == null ? null : request.get("ticket");
        if (ticket == null || !ticket.isObject() || ticket.get("name") == null || ticket.get("name").isNull()) {
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
        return new TicketReplyDraft(reply, llm.provider(), llm.model());
    }

    /** Live intent on a care chat: what the customer is really asking, from the
     * transcript so far. FAST tier, strict labeled lines; the desk shows the
     * intent as a chip and the suggested reply as a draft the agent may use. */
    @Transactional
    public ChatIntent chatIntent(JsonNode request) {
        JsonNode messages = request == null ? null : request.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw new BadRequestException("messages [{author, body}] are required");
        }
        String system = "You are a chat intent classifier for a telecom care desk. Read the"
                + " conversation and say what the CUSTOMER wants right now. Respond with ONLY"
                + " these labeled lines and nothing else:\n"
                + "INTENT: <one of: connectivity, billing, sim, plan-change, cancellation, delivery, porting, other>\n"
                + "CONFIDENCE: <0.0-1.0>\n"
                + "SUMMARY: <one sentence, what they want, in the agent's words>\n"
                + "REPLY: <a short, empathetic next message the agent could send, max 300 characters, no promises>";
        String user = "Conversation (JSON, oldest first):\n" + contextOf(request);
        String raw = completeWithRetry(system, user, "chat-intent");
        String intent = lineAfter(raw, "INTENT:");
        String summary = lineAfter(raw, "SUMMARY:");
        String reply = lineAfter(raw, "REPLY:");
        if (intent == null || summary == null) {
            throw new BadRequestException("the model did not follow the INTENT/SUMMARY contract");
        }
        double confidence;
        try {
            confidence = Math.max(0, Math.min(1, Double.parseDouble(String.valueOf(lineAfter(raw, "CONFIDENCE:")).trim())));
        } catch (Exception e) {
            confidence = 0.5;
        }
        return new ChatIntent(intent.trim().toLowerCase().replaceAll("[^a-z-]", ""), confidence,
                summary, reply, llm.provider(), llm.model());
    }

    /** After-call work drafted from what actually happened: the situation the
     * desk saw, the actions logged during the call, the notes. The agent edits
     * and logs it; nothing is written by the model. */
    @Transactional
    public WrapUp wrapUp(JsonNode request) {
        String system = "You are a telecom customer-service copilot writing the after-call note."
                + " From the call record, write what the customer contacted us about, what was"
                + " done, and what is still open. Only state what the record shows. Respond with"
                + " ONLY these labeled lines and nothing else:\n"
                + "NOTE: <2-4 sentences, past tense, the contact reason, what was done, what remains>\n"
                + "DISPOSITION: <one of: resolved, follow-up, escalated, informational>\n"
                + "FOLLOWUP: <one concrete follow-up or 'none'>";
        String user = "Call record (JSON):\n" + contextOf(request);
        String raw = completeWithRetry(system, user, "wrap-up");
        String note = lineAfter(raw, "NOTE:");
        if (note == null) {
            throw new BadRequestException("the model did not follow the NOTE contract");
        }
        String follow = lineAfter(raw, "FOLLOWUP:");
        return new WrapUp(note,
                String.valueOf(lineAfter(raw, "DISPOSITION:") == null ? "informational" : lineAfter(raw, "DISPOSITION:")).trim().toLowerCase(),
                follow == null || follow.trim().equalsIgnoreCase("none") ? null : follow.trim(),
                llm.provider(), llm.model());
    }

    /** Serialize and cap whatever slice of the 360 the console sent. Personal
     * values are redacted by the governor before the prompt leaves — and put
     * back into the answer — so nothing is masked here any more. */
    private String contextOf(JsonNode request) {
        if (request == null || request.isEmpty()) {
            throw new BadRequestException("a context payload is required");
        }
        try {
            String json = objectMapper.writeValueAsString(request);
            if (json.length() > CONTEXT_CHARS) {
                json = json.substring(0, CONTEXT_CHARS);
            }
            return json;
        } catch (Exception e) {
            throw new BadRequestException("context payload is not serializable");
        }
    }

    private String completeWithRetry(String system, String user, String useCase) {
        // through the governor: metered, budgeted, audited in one place
        String raw = governor.complete(useCase,
                com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user);
        if (looksUnlabeled(raw)) {
            raw = governor.complete(useCase + "-retry",
                    com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, user
                    + "\nYour previous answer did not follow the format. Respond again using"
                    + " ONLY the labeled lines from the instructions.");
        }
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

}
