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

    /** Cached answers live at most this long even when the articles did not change. */
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;
    private static final int CACHE_MAX = 2000;

    private final KnowledgeClient knowledge;
    private final LlmAdapter llm;

    private final com.bss.intelligence.llm.AiGovernor governor;
    private final com.bss.intelligence.knowledge.KnowledgeGapRepository gaps;
    private final com.bss.intelligence.security.TenantScope tenantScope;

    /** tenant|audienceKey|question -> cached answer. Retrieval (free) still runs every time;
     *  the model (metered) only runs when the retrieved articles changed since the cached answer. */
    private final Map<String, Cached> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private record Cached(String fingerprint, Map<String, Object> answer, long at) { }

    public KnowledgeAskService(KnowledgeClient knowledge, LlmAdapter llm,
            com.bss.intelligence.llm.AiGovernor governor,
            com.bss.intelligence.knowledge.KnowledgeGapRepository gaps,
            com.bss.intelligence.security.TenantScope tenantScope) {
        this.knowledge = knowledge;
        this.llm = llm;
        this.governor = governor;
        this.gaps = gaps;
        this.tenantScope = tenantScope;
    }

    public Map<String, Object> ask(String bearerToken, String question) {
        return ask(bearerToken, question, null);
    }

    /** @param screen the screen the question came from (e.g. "pane:approvals") — recorded with a gap. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> ask(String bearerToken, String question, String screen) {
        List<Map<String, Object>> hits = knowledge.searchAs(bearerToken, question);
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            recordGap(question, screen);
            out.put("answer", "I could not find anything about that in the knowledge base. "
                    + "Try other words, or raise a ticket and a human will pick it up.");
            out.put("sources", List.of());
            out.put("gap", true);
            return out;
        }
        List<Map<String, Object>> top = hits.subList(0, Math.min(TOP, hits.size()));
        // the cache key is the asker's shelf (what they could read) + the question; the
        // fingerprint is the retrieved articles + their lastUpdate, so an edited article
        // invalidates every answer that drew on it — without any event plumbing
        String normalised = question.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        StringBuilder fp = new StringBuilder();
        for (Map<String, Object> a : top) {
            fp.append(a.get("id")).append('@').append(a.get("lastUpdate")).append(';');
        }
        String key = tenantScope.currentTenantId() + "|" + fp + "|" + normalised;
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) {
            Map<String, Object> cached = new LinkedHashMap<>(c.answer());
            cached.put("cached", true);
            return cached;
        }
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
        String answer = governor.complete("knowledge-ask",
                com.bss.intelligence.llm.LlmAdapter.Tier.FAST, system, question);
        out.put("answer", answer);
        out.put("sources", sources);
        out.put("provider", llm.provider());
        out.put("model", llm.model());
        out.put("cached", false);
        cache.put(key, new Cached(fp.toString(), new LinkedHashMap<>(out), System.currentTimeMillis()));
        return out;
    }

    private void recordGap(String question, String context) {
        String q = question == null ? "" : question.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (q.length() < 3) {
            return;
        }
        q = q.substring(0, Math.min(500, q.length()));
        String tenant = tenantScope.currentTenantId();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        com.bss.intelligence.knowledge.KnowledgeGap gap = gaps.findByTenantIdAndQuestion(tenant, q).orElse(null);
        if (gap == null) {
            gap = new com.bss.intelligence.knowledge.KnowledgeGap();
            gap.setId(java.util.UUID.randomUUID().toString());
            gap.setTenantId(tenant);
            gap.setQuestion(q);
            gap.setAsked(0);
            gap.setFirstAsked(now);
        }
        gap.setAsked(gap.getAsked() + 1);
        gap.setLastAsked(now);
        if (context != null && !context.isBlank()) {
            gap.setContext(context.substring(0, Math.min(120, context.length())));
        }
        gaps.save(gap);
    }

    /** The content team's to-do list: what people asked that no article answered. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> gaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.bss.intelligence.knowledge.KnowledgeGap g : gaps.findTop50ByTenantIdOrderByAskedDescLastAskedDesc(tenantScope.currentTenantId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("question", g.getQuestion());
            m.put("context", g.getContext());
            m.put("asked", g.getAsked());
            m.put("firstAsked", g.getFirstAsked().toString());
            m.put("lastAsked", g.getLastAsked().toString());
            out.add(m);
        }
        return out;
    }

    public int cacheSize() {
        return cache.size();
    }
}
