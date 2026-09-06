package com.bss.insight.service;

import com.bss.insight.entity.SocialMention;
import com.bss.insight.repository.SocialMentionRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Social listening: pull brand mentions from the platform, score sentiment, and
 * summarize mood + share-of-voice. Sentiment here is a transparent keyword STUB
 * — the production scorer is the intelligence LLM; the seam and storage are what
 * matter. Idempotent per external id, so re-syncing never double-counts.
 */
@Service
public class SocialListeningService {

    private static final Set<String> POSITIVE = Set.of("love", "great", "good", "awesome", "fast",
            "happy", "best", "excellent", "amazing", "recommend");
    private static final Set<String> NEGATIVE = Set.of("hate", "bad", "slow", "down", "terrible",
            "worst", "angry", "broken", "outage", "buggy", "awful", "scam");

    private final SocialMentionRepository mentions;
    private final SignalService signalService;
    private final TenantScope tenantScope;
    private final com.bss.insight.social.SocialProviders providers;

    public SocialListeningService(SocialMentionRepository mentions, SignalService signalService,
            TenantScope tenantScope, com.bss.insight.social.SocialProviders providers) {
        this.mentions = mentions;
        this.signalService = signalService;
        this.tenantScope = tenantScope;
        this.providers = providers;
    }

    /** Pull the brand's mentions, score sentiment, store the new ones. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> sync() {
        String tenantId = tenantScope.currentTenantId();
        int ingested = 0;
        com.bss.insight.social.SocialConfig cfg = providers.current();
        boolean enabled = cfg.enabled();
        if (enabled) {
            List<Map<String, Object>> data = providers.providerFor(cfg).mentions(cfg);
            for (Map<String, Object> m : data) {
                String platform = String.valueOf(m.getOrDefault("platform", "x"));
                String externalId = m.get("id") == null ? null : String.valueOf(m.get("id"));
                if (externalId == null
                        || mentions.existsByTenantIdAndPlatformAndExternalId(tenantId, platform, externalId)) {
                    continue;
                }
                SocialMention sm = new SocialMention();
                sm.setId(UUID.randomUUID().toString());
                sm.setTenantId(tenantId);
                sm.setPlatform(platform);
                sm.setExternalId(externalId);
                sm.setAuthor(m.get("author") == null ? null : String.valueOf(m.get("author")));
                String text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
                sm.setText(text);
                sm.setSentiment(score(text));
                sm.setCreatedAt(OffsetDateTime.now());
                mentions.save(sm);
                // a mention IS a customer signal (SI-P1) — same door, same firewall;
                // a failed ingest must never break the listening sync
                try {
                    signalService.ingest(java.util.Map.of(
                            "source", "mention", "sourceRef", platform + ":" + externalId,
                            "channel", "social", "text", text,
                            "context", java.util.Map.of("platform", platform, "sentiment", sm.getSentiment())));
                } catch (RuntimeException e) {
                    // non-fatal by design
                }
                ingested++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", ingested);
        out.put("enabled", enabled);
        return out;
    }

    /** Share-of-mood + by-platform, for a listening dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<SocialMention> all = mentions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        Map<String, Integer> sentiment = new LinkedHashMap<>(Map.of("positive", 0, "neutral", 0, "negative", 0));
        Map<String, Integer> byPlatform = new TreeMap<>();
        for (SocialMention m : all) {
            sentiment.merge(m.getSentiment() == null ? "neutral" : m.getSentiment(), 1, Integer::sum);
            byPlatform.merge(m.getPlatform() == null ? "other" : m.getPlatform(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("sentiment", sentiment);
        out.put("byPlatform", byPlatform);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent() {
        return mentions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().limit(100).map(m -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", m.getId());
                    o.put("platform", m.getPlatform());
                    o.put("author", m.getAuthor());
                    o.put("text", m.getText());
                    o.put("sentiment", m.getSentiment());
                    o.put("createdAt", m.getCreatedAt());
                    return o;
                }).toList();
    }

    /** Transparent keyword classifier — swap for the intelligence LLM in prod. */
    private static String score(String text) {
        String t = text == null ? "" : text.toLowerCase();
        boolean pos = POSITIVE.stream().anyMatch(t::contains);
        boolean neg = NEGATIVE.stream().anyMatch(t::contains);
        if (pos && !neg) return "positive";
        if (neg && !pos) return "negative";
        return "neutral";
    }
}
