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

    private final com.bss.intelligence.client.OntologyClient ontology;

    public KnowledgeAskService(KnowledgeClient knowledge, LlmAdapter llm,
            com.bss.intelligence.llm.AiGovernor governor,
            com.bss.intelligence.knowledge.KnowledgeGapRepository gaps,
            com.bss.intelligence.security.TenantScope tenantScope,
            com.bss.intelligence.client.OntologyClient ontology) {
        this.ontology = ontology;
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
        Retrieval retrieval = retrieve(bearerToken, question, screen);
        List<Map<String, Object>> hits = retrieval.hits();
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            recordGap(question, screen);
            out.put("answer", "I could not find anything about that in the knowledge base. "
                    + "Try other words, or raise a ticket and a human will pick it up.");
            out.put("sources", List.of());
            out.put("gap", true);
            return out;
        }
        if (!retrieval.questionMatched()) {
            // the screen's own shelf will still try to answer, but nothing matched the
            // question's words — that is a gap for the content team whatever the model says
            recordGap(question, screen);
            out.put("gap", true);
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
        String where = screen == null || screen.isBlank() ? ""
                : " The person is asking from the screen \"" + screenName(screen) + "\" of this BSS;"
                + " articles tagged for that screen describe it — when they ask what the page is for"
                + " or how to use it, explain from those first.";
        String system = "You are the knowledge assistant of a telecom operator, and this BSS explains"
                + " itself: the articles below include its own Operator's Manual and screen help." + where
                + " Answer the question using ONLY the articles below. Be concise and practical;"
                + " give numbered steps when the question is how to do something; name the"
                + " article title you drew from. Write plain text for a small side panel: short"
                + " paragraphs and numbered lines, no markdown headings, no bold markers, no code"
                + " fences. If the articles do not cover it, say so"
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

    /**
     * Page-aware retrieval: the keyword hits that carry the screen's tag come first, then
     * the other keyword hits, then the rest of the screen's own shelf — so "how do I use
     * this?" asked from a page is answered from that page's help and manual section even
     * when the words of the question match nothing. Only when both are empty is it a gap.
     */
    record Retrieval(List<Map<String, Object>> hits, boolean questionMatched) { }

    Retrieval retrieve(String bearerToken, String question, String screen) {
        List<Map<String, Object>> byWords = new ArrayList<>(knowledge.searchAs(bearerToken, question));
        // keyword search is AND-shaped: "how do I use the simulator" must contain every word.
        // A second pass with only the content words catches the article that says "simulator"
        // but never "use".
        String gist = gistOf(question);
        List<Map<String, Object>> byGist = !gist.isEmpty() && !gist.equalsIgnoreCase(question.trim())
                ? knowledge.searchAs(bearerToken, gist) : List.of();
        boolean paged = screen != null && !screen.isBlank();
        List<Map<String, Object>> shelf = paged ? knowledge.shelfAs(bearerToken, screen.trim()) : List.of();
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        // 0. the ontology's own words for this screen, when it has an entry: what the page
        //    manages and which governed actions it offers — generated from the registry
        if (paged && screen.startsWith("pane:")) {
            Map<String, Object> structural = ontology.pageArticle(bearerToken, screen.substring("pane:".length()));
            if (structural != null) {
                ordered.put(String.valueOf(structural.get("id")), structural);
            }
        }
        // 1. keyword hits that belong to this screen
        if (paged) {
            for (Map<String, Object> a : byWords) {
                if (hasTag(a, screen)) {
                    ordered.putIfAbsent(String.valueOf(a.get("id")), a);
                }
            }
        }
        // 2. the screen's own shelf — guaranteed slots, so a page's help is never crowded out
        //    by loose keyword matches ("sim" finding eSIM articles for the prospect simulator)
        int reserved = 0;
        for (Map<String, Object> a : shelf) {
            if (reserved >= SHELF_SLOTS) {
                break;
            }
            if (ordered.putIfAbsent(String.valueOf(a.get("id")), a) == null) {
                reserved++;
            }
        }
        // 3. the rest of the keyword hits, then the content-word hits, then the rest of the shelf
        for (Map<String, Object> a : byWords) {
            ordered.putIfAbsent(String.valueOf(a.get("id")), a);
        }
        for (Map<String, Object> a : byGist) {
            ordered.putIfAbsent(String.valueOf(a.get("id")), a);
        }
        for (Map<String, Object> a : shelf) {
            ordered.putIfAbsent(String.valueOf(a.get("id")), a);
        }
        return new Retrieval(new ArrayList<>(ordered.values()), !byWords.isEmpty() || !byGist.isEmpty());
    }

    /** How many of the TOP articles the asker's own screen may claim before keyword hits fill the rest. */
    private static final int SHELF_SLOTS = 3;

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "how", "do", "does", "i", "we", "you", "to", "use", "using", "the", "a", "an", "this", "that",
            "what", "is", "are", "for", "and", "or", "of", "in", "on", "it", "its", "my", "me", "can",
            "should", "would", "page", "tab", "screen", "step", "by", "steps", "with", "where", "when",
            "which", "why", "who", "please", "help", "want", "need", "there", "here", "get", "make");

    /** The content words of a question: "how do I use the simulator?" → "simulator". */
    static String gistOf(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String w : question.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 1 && !STOPWORDS.contains(w)) {
                sb.append(sb.length() == 0 ? "" : " ").append(w);
            }
        }
        return sb.toString();
    }

    private static boolean hasTag(Map<String, Object> article, String tag) {
        Object tags = article.get("tags");
        if (tags == null) {
            return false;
        }
        for (String t : String.valueOf(tags).split(",")) {
            if (t.trim().equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    /** "pane:simulate/priceChange" → "simulate/priceChange": the screen in the console's own words. */
    private static String screenName(String screen) {
        int i = screen.indexOf(':');
        return i < 0 ? screen : screen.substring(i + 1);
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

    @org.springframework.transaction.annotation.Transactional
    public void dismiss(String id) {
        gaps.findById(id).filter(g -> g.getTenantId().equals(tenantScope.currentTenantId())).ifPresent(gaps::delete);
    }

    public int cacheSize() {
        return cache.size();
    }
}
