package com.bss.intelligence.service;

import com.bss.intelligence.client.KnowledgeClient;
import com.bss.intelligence.llm.LlmAdapter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ask the knowledge base: retrieve first, then let the model answer FROM the
 * retrieved articles only — grounded, with the sources named. No articles
 * found means an honest "I don't know", never a guess. The searching happens
 * with the ASKER's token, so the answer can only draw on what they could
 * read themselves.
 */
@Service
public class KnowledgeAskService {

    private static final int TOP = 5;

    private final KnowledgeClient knowledge;
    private final LlmAdapter llm;

    public KnowledgeAskService(KnowledgeClient knowledge, LlmAdapter llm) {
        this.knowledge = knowledge;
        this.llm = llm;
    }

    public Map<String, Object> ask(String bearerToken, String question) {
        List<Map<String, Object>> hits = knowledge.searchAs(bearerToken, question);
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            out.put("answer", "I could not find anything about that in the knowledge base. "
                    + "Try other words, or raise a ticket and a human will pick it up.");
            out.put("sources", List.of());
            return out;
        }
        List<Map<String, Object>> top = hits.subList(0, Math.min(TOP, hits.size()));
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> a : top) {
            context.append("TITLE: ").append(a.get("title")).append('\n')
                    .append(a.get("body")).append("\n---\n");
            sources.add(Map.of("id", String.valueOf(a.get("id")),
                    "title", String.valueOf(a.get("title"))));
        }
        String system = "You are the knowledge assistant of a telecom operator. Answer the"
                + " question using ONLY the articles below. Be concise and practical; name the"
                + " article title you drew from. If the articles do not cover it, say so"
                + " plainly and suggest raising a ticket. Never invent policies or prices.\n\n"
                + "ARTICLES:\n" + context;
        String answer = llm.complete(com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, question);
        out.put("answer", answer);
        out.put("sources", sources);
        out.put("provider", llm.provider());
        out.put("model", llm.model());
        return out;
    }
}
