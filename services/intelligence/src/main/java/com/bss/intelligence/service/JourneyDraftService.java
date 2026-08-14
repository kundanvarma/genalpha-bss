package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-native journey authoring: describe a journey in a sentence and the model
 * drafts a staged plan — a welcome, an activation nudge, a follow-up — each
 * with written copy. It is a PROPOSAL: the draft comes back for a human to
 * review and Create; nothing is deployed and no customer is messaged here.
 * Every model call rides the governor — metered, budgeted and audited — so
 * the AI carries a receipt, the same posture as every other AI feature.
 *
 * The structure is a safe scaffold the marketer edits; the model fills the
 * copy. Output is the exact journey object the campaign engine accepts, so
 * what the author approves is what runs — no draw-vs-run drift.
 */
@Service
public class JourneyDraftService {

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final com.bss.intelligence.llm.AiGovernor governor;

    /** The default plan when the author doesn't specify stages. */
    private static final List<String[]> DEFAULT_STAGES = List.of(
            new String[] {"Welcome", "warmly greet a brand-new customer who just signed up"},
            new String[] {"Activate", "nudge the customer to add or activate their first service"},
            new String[] {"Follow-up", "check in about a week later and offer help"});

    public JourneyDraftService(LlmAdapter llm, Redactor redactor,
            com.bss.intelligence.llm.AiGovernor governor) {
        this.llm = llm;
        this.redactor = redactor;
        this.governor = governor;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> draftJourney(Map<String, Object> request) {
        if (request.get("brief") == null || String.valueOf(request.get("brief")).isBlank()) {
            throw new BadRequestException("brief is required");
        }
        String brief = redactor.redact(String.valueOf(request.get("brief")));
        String brandName = request.get("brandName") == null ? "the operator"
                : redactor.redact(String.valueOf(request.get("brandName")));

        // stages: caller-supplied [{stage, intent}] or the default scaffold
        List<String[]> stages = new ArrayList<>();
        if (request.get("stages") instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("stage") != null) {
                    stages.add(new String[] {String.valueOf(m.get("stage")),
                            m.get("intent") == null ? "message the customer" : String.valueOf(m.get("intent"))});
                }
            }
        }
        if (stages.isEmpty()) stages.addAll(DEFAULT_STAGES);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            String stage = stages.get(i)[0];
            String intent = stages.get(i)[1];
            String[] copy = draftStageCopy(brandName, brief, stage, intent);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "message");
            msg.put("stage", stage);
            msg.put("subject", copy[0]);
            msg.put("content", copy[1]);
            steps.add(msg);
            if (i < stages.size() - 1) {
                Map<String, Object> wait = new LinkedHashMap<>();
                wait.put("type", "wait");
                wait.put("stage", stage);
                wait.put("days", 3);
                steps.add(wait);
            }
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "Journey: " + trim(brief, 48));
        draft.put("triggerEventType", "IndividualCreateEvent");
        draft.put("holdoutPercent", 10);
        draft.put("steps", steps);
        draft.put("provider", llm.provider());
        draft.put("model", llm.model());
        draft.put("note", "AI draft — review the stages and copy, then Create. Nothing is live until you do.");
        return draft;
    }

    private String[] draftStageCopy(String brandName, String brief, String stage, String intent) {
        String system = "You write short, warm lifecycle marketing messages for " + brandName
                + ", a telecom brand. Respond with ONLY two lines and nothing else, exactly:\n"
                + "SUBJECT: <subject, max 60 characters>\n"
                + "BODY: <body, max 300 characters, no emojis>";
        String user = "Journey brief: " + brief + "\n"
                + "This message is the '" + stage + "' stage. Its job: " + intent + ".";
        String raw = governor.complete("journey-draft", LlmAdapter.Tier.FAST, system, user);
        String subject = lineAfter(raw, "SUBJECT:");
        String body = lineAfter(raw, "BODY:");
        if (subject == null || body == null) {
            raw = governor.complete("journey-draft-retry", LlmAdapter.Tier.FAST, system,
                    user + "\nRespond again with ONLY the two lines 'SUBJECT:' and 'BODY:'.");
            subject = lineAfter(raw, "SUBJECT:");
            body = lineAfter(raw, "BODY:");
        }
        // safe fallback: never hand back a blank stage, even if the model misbehaves
        if (subject == null || subject.isBlank()) subject = stage;
        if (body == null || body.isBlank()) body = "Hi! " + intent;
        return new String[] {subject, body};
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }

    private static String lineAfter(String raw, String label) {
        if (raw == null) return null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim().replaceFirst("^[*#>\\-\\s]+", "");
            if (trimmed.regionMatches(true, 0, label, 0, label.length())) {
                String value = trimmed.substring(label.length()).replaceAll("^[*\\s]+|[*\\s]+$", "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return null;
    }
}
