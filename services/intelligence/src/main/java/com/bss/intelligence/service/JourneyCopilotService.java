package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The journey & campaign copilot: a marketer CHATS about the outreach they
 * want; the copilot asks clarifying questions and, when it has enough, returns
 * a complete PROPOSAL — a multi-step journey or a single-message campaign. It
 * only ever proposes: the console shows the proposal as a human-readable card
 * and, on confirm, applies it with the marketer's OWN token against the
 * campaign API. The model never holds credentials and never writes. Every turn
 * rides the governor — metered, budgeted, audited.
 */
@Service
public class JourneyCopilotService {

    private static final int HISTORY_TURNS = 12;

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final com.bss.intelligence.llm.AiGovernor governor;
    private final ObjectMapper objectMapper;

    public JourneyCopilotService(LlmAdapter llm, Redactor redactor,
            com.bss.intelligence.llm.AiGovernor governor, ObjectMapper objectMapper) {
        this.llm = llm;
        this.redactor = redactor;
        this.governor = governor;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(Map<String, Object> request) {
        if (!(request.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            throw new BadRequestException("messages [{role, content}] are required");
        }
        String system = """
                You are the journey & campaign copilot of a TM Forum ODA telecom BSS. A \
                marketer describes an outreach; you help them build it. Two artifacts:
                - A JOURNEY is an ordered set of steps: message {channel: inApp|email|sms|push, \
                subject, content, stage?}, wait {days|hours}, waitForEvent {event, days (timeout), \
                onTimeout: {subject, content}}, decision {inSegment, then/else messages or \
                thenNext/elseNext to route to other step ids}, exit.
                - A CAMPAIGN is one trigger + one message (+ optional promotionCode).
                TRIGGERS: IndividualCreateEvent (a customer registers = onboarding), \
                ProductOrderStateChangeEvent (order activated), ShoppingCartAbandonedEvent \
                (cart abandoned), ChurnRiskDetectedEvent (AI churn scorer), \
                LoyaltyTierChangedEvent, CustomerBillCreateEvent. Leave triggerEventType blank \
                and set segmentName for a segment blast.
                A holdoutPercent (0-90) is a control group that gets NO message, so lift is \
                measured not guessed. A priority (>0) enters the journey into next-best-action \
                arbitration; leave 0 unless they ask.
                Respond with ONLY a JSON object, no markdown fences, shaped: \
                {"kind":"question"|"advice"|"proposal","message":"<what you say to the marketer>",\
                "proposal": null or {"artifact":"journey"|"campaign",\
                "journey": {"name","triggerEventType"?,"segmentName"?,"holdoutPercent","priority",\
                "steps":[...]},\
                "campaign": {"name","triggerEventType"?,"segmentName"?,"holdoutPercent",\
                "message":{"subject","content"},"promotionCode"?}}}
                Include only the artifact you propose (journey OR campaign). \
                PERSONALIZE the copy with tokens resolved per-customer at send time: \
                {{party.firstName}} (greet by name), {{brand.name}}, and — when the trigger \
                carries them — {{order.id}}, {{tracking.url}} (a shipped-handset message), \
                {{usage.remaining}}/{{usage.percentUsed}} (a UsageThresholdBreachedEvent "running \
                low" message), and {{organization.name}} for a B2B account's company name. \
                Prefer greeting by first name in the opening line.
                Ask a QUESTION when the ask is ambiguous (who is it for? what triggers it? one \
                message or a series?); give ADVICE when they want to understand; PROPOSE when \
                they have answered or asked you to create. Keep copy short and warm; default a \
                10% holdout so lift is measurable.""";

        StringBuilder conversation = new StringBuilder();
        List<Map<String, Object>> turns = (List<Map<String, Object>>) messages;
        for (Map<String, Object> turn : turns.subList(Math.max(0, turns.size() - HISTORY_TURNS), turns.size())) {
            conversation.append("owner".equalsIgnoreCase(String.valueOf(turn.get("role")))
                    || "user".equalsIgnoreCase(String.valueOf(turn.get("role"))) ? "OWNER: " : "COPILOT: ");
            conversation.append(redactor.redact(String.valueOf(turn.getOrDefault("content", "")))).append("\n");
        }

        String raw = governor.complete("journey-copilot", LlmAdapter.Tier.SMART, system, conversation.toString());
        Map<String, Object> parsed = parse(raw);
        if (parsed == null) {
            raw = governor.complete("journey-copilot-retry", LlmAdapter.Tier.SMART, system, conversation
                    + "\nYour previous answer was not the required bare JSON object. Respond again"
                    + " with ONLY the JSON object described in the instructions.");
            parsed = parse(raw);
        }
        if (parsed == null) {
            throw new BadRequestException("the model did not follow the copilot JSON contract");
        }
        parsed.put("provider", llm.provider());
        parsed.put("model", llm.model());
        return parsed;
    }

    /** Markdown-tolerant JSON parse: strip fences, take the outermost object. */
    private Map<String, Object> parse(String raw) {
        if (raw == null) return null;
        String body = raw.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(body.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() { });
            String kind = String.valueOf(parsed.get("kind"));
            if (!List.of("question", "advice", "proposal").contains(kind) || parsed.get("message") == null) {
                return null;
            }
            if ("proposal".equals(kind) && !(parsed.get("proposal") instanceof Map)) {
                return null;
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }
}
