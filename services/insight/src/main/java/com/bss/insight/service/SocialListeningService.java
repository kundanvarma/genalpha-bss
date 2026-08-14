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
    private final TenantScope tenantScope;
    private final RestClient social;
    private final String accountId;
    private final String token;
    private final boolean enabled;

    public SocialListeningService(SocialMentionRepository mentions, TenantScope tenantScope,
            RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String baseUrl,
            @Value("${bss.downstream.social-account-id:}") String accountId,
            @Value("${bss.downstream.social-access-token:}") String token) {
        this.mentions = mentions;
        this.tenantScope = tenantScope;
        this.social = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
        this.accountId = accountId;
        this.token = token;
        this.enabled = baseUrl != null && !baseUrl.isBlank() && accountId != null && !accountId.isBlank();
    }

    /** Pull the brand's mentions, score sentiment, store the new ones. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> sync() {
        String tenantId = tenantScope.currentTenantId();
        int ingested = 0;
        if (enabled) {
            Map<String, Object> body = social.get().uri("/v1/{acct}/mentions", accountId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> data = body != null && body.get("data") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
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
