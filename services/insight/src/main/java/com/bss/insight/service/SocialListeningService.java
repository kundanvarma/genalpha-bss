package com.bss.insight.service;

import com.bss.insight.dto.SignalInput;
import com.bss.insight.dto.SocialListeningDtos;
import com.bss.insight.dto.SocialMessage;
import com.bss.insight.entity.SocialMention;
import com.bss.insight.repository.SocialMentionRepository;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public SocialListeningDtos.SyncReceipt sync() {
        String tenantId = tenantScope.currentTenantId();
        int ingested = 0;
        com.bss.insight.social.SocialConfig cfg = providers.current();
        boolean enabled = cfg.enabled();
        if (enabled) {
            List<SocialMessage> data = providers.providerFor(cfg).mentions(cfg);
            for (SocialMessage m : data) {
                String platform = m.platformOr("x");
                String externalId = m.id();
                if (externalId == null
                        || mentions.existsByTenantIdAndPlatformAndExternalId(tenantId, platform, externalId)) {
                    continue;
                }
                SocialMention sm = new SocialMention();
                sm.setId(UUID.randomUUID().toString());
                sm.setTenantId(tenantId);
                sm.setPlatform(platform);
                sm.setExternalId(externalId);
                sm.setAuthor(m.author());
                String text = m.textOrEmpty();
                sm.setText(text);
                sm.setSentiment(score(text));
                sm.setCreatedAt(OffsetDateTime.now());
                mentions.save(sm);
                // a mention IS a customer signal (SI-P1) — same door, same firewall;
                // a failed ingest must never break the listening sync
                try {
                    signalService.ingest(new SignalInput("mention", text, platform + ":" + externalId, null, "social",
                            null, JsonNodeFactory.instance.objectNode()
                                    .put("platform", platform).put("sentiment", sm.getSentiment())));
                } catch (RuntimeException e) {
                    // non-fatal by design
                }
                ingested++;
            }
        }
        return new SocialListeningDtos.SyncReceipt(ingested, enabled);
    }

    /** Share-of-mood + by-platform, for a listening dashboard. */
    @Transactional(readOnly = true)
    public SocialListeningDtos.Summary summary() {
        List<SocialMention> all = mentions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        Map<String, Integer> sentiment = new LinkedHashMap<>();
        sentiment.put("positive", 0);
        sentiment.put("neutral", 0);
        sentiment.put("negative", 0);
        Map<String, Integer> byPlatform = new TreeMap<>();
        for (SocialMention m : all) {
            sentiment.merge(m.getSentiment() == null ? "neutral" : m.getSentiment(), 1, Integer::sum);
            byPlatform.merge(m.getPlatform() == null ? "other" : m.getPlatform(), 1, Integer::sum);
        }
        return new SocialListeningDtos.Summary(all.size(), sentiment, byPlatform);
    }

    @Transactional(readOnly = true)
    public List<SocialListeningDtos.Mention> recent() {
        return mentions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().limit(100).map(m -> new SocialListeningDtos.Mention(m.getId(), m.getPlatform(), m.getAuthor(),
                        m.getText(), m.getSentiment(), m.getCreatedAt())).toList();
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
