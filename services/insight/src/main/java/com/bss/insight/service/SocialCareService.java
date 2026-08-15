package com.bss.insight.service;

import com.bss.insight.entity.SocialDm;
import com.bss.insight.events.DomainEventPublisher;
import com.bss.insight.repository.SocialDmRepository;
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
import java.util.UUID;

/**
 * Social care: pull inbound DIRECT MESSAGES from the platform, score sentiment +
 * support intent, and — for the ones that need a human — request a trouble
 * ticket by EMITTING an event, not by calling the ticket service. insight has no
 * ticket-write scope and shouldn't: the operational bus carries the request, the
 * trouble-ticket service owns the case (TMF621). Idempotent per external id, so
 * re-syncing never double-opens a ticket.
 */
@Service
public class SocialCareService {

    private static final Set<String> NEGATIVE = Set.of("hate", "bad", "slow", "down", "terrible",
            "worst", "angry", "broken", "outage", "buggy", "awful", "scam", "cancel", "refund");
    // Intent, not mood: a calm "how do I…" still needs an agent.
    private static final Set<String> SUPPORT = Set.of("help", "support", "how do i", "how to", "can't",
            "cannot", "not working", "issue", "problem", "error", "fix", "broken", "outage", "down",
            "charged", "bill", "refund", "cancel", "reset", "password");
    private static final Set<String> POSITIVE = Set.of("love", "great", "good", "awesome", "fast",
            "happy", "best", "excellent", "amazing", "recommend", "thanks", "thank you");

    private final SocialDmRepository dms;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final RestClient social;
    private final String accountId;
    private final String token;
    private final boolean enabled;

    public SocialCareService(SocialDmRepository dms, DomainEventPublisher events, TenantScope tenantScope,
            RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String baseUrl,
            @Value("${bss.downstream.social-account-id:}") String accountId,
            @Value("${bss.downstream.social-access-token:}") String token) {
        this.dms = dms;
        this.events = events;
        this.tenantScope = tenantScope;
        this.social = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
        this.accountId = accountId;
        this.token = token;
        this.enabled = baseUrl != null && !baseUrl.isBlank() && accountId != null && !accountId.isBlank();
    }

    /** Pull DMs, score them, store the new ones, and request tickets for the ones that need care. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> sync() {
        String tenantId = tenantScope.currentTenantId();
        int ingested = 0;
        int ticketsRequested = 0;
        if (enabled) {
            Map<String, Object> body = social.get().uri("/v1/{acct}/dms", accountId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> data = body != null && body.get("data") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            for (Map<String, Object> m : data) {
                String platform = String.valueOf(m.getOrDefault("platform", "x"));
                String externalId = m.get("id") == null ? null : String.valueOf(m.get("id"));
                if (externalId == null
                        || dms.existsByTenantIdAndPlatformAndExternalId(tenantId, platform, externalId)) {
                    continue; // idempotent: a DM is ingested (and ticketed) exactly once
                }
                String text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
                String sentiment = score(text);
                boolean needsCare = "negative".equals(sentiment) || isSupport(text);

                SocialDm dm = new SocialDm();
                dm.setId(UUID.randomUUID().toString());
                dm.setTenantId(tenantId);
                dm.setPlatform(platform);
                dm.setExternalId(externalId);
                dm.setAuthor(m.get("author") == null ? null : String.valueOf(m.get("author")));
                dm.setHandle(m.get("handle") == null ? null : String.valueOf(m.get("handle")));
                dm.setText(text);
                dm.setSentiment(sentiment);
                dm.setNeedsCare(needsCare);
                dm.setCreatedAt(OffsetDateTime.now());

                if (needsCare) {
                    // The event IS the ticket request — the trouble-ticket service
                    // consumes it and opens the case. Deterministic key so a
                    // re-delivery dedupes to the same ticket.
                    Map<String, Object> req = new LinkedHashMap<>();
                    req.put("sourceId", "social-dm:" + platform + ":" + externalId);
                    req.put("platform", platform);
                    req.put("author", dm.getAuthor());
                    req.put("handle", dm.getHandle());
                    req.put("text", text);
                    req.put("sentiment", sentiment);
                    req.put("reason", "negative".equals(sentiment) ? "negative-sentiment" : "support-request");
                    events.publish("SocialCareTicketRequested", "socialCareRequest", req, tenantId);
                    dm.setTicketRequested(true);
                    ticketsRequested++;
                }
                dms.save(dm);
                ingested++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", ingested);
        out.put("ticketsRequested", ticketsRequested);
        out.put("enabled", enabled);
        return out;
    }

    /** Care-queue health: how many DMs, how many need a human, mood split. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<SocialDm> all = dms.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        Map<String, Integer> sentiment = new LinkedHashMap<>(Map.of("positive", 0, "neutral", 0, "negative", 0));
        int needCare = 0;
        for (SocialDm m : all) {
            sentiment.merge(m.getSentiment() == null ? "neutral" : m.getSentiment(), 1, Integer::sum);
            if (m.isNeedsCare()) {
                needCare++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("needCare", needCare);
        out.put("sentiment", sentiment);
        return out;
    }

    /** The care queue: recent DMs, newest first, with their triage. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        return dms.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().limit(100).map(m -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", m.getId());
                    o.put("platform", m.getPlatform());
                    o.put("author", m.getAuthor());
                    o.put("handle", m.getHandle());
                    o.put("text", m.getText());
                    o.put("sentiment", m.getSentiment());
                    o.put("needsCare", m.isNeedsCare());
                    o.put("ticketRequested", m.isTicketRequested());
                    o.put("createdAt", m.getCreatedAt());
                    return o;
                }).toList();
    }

    /** Transparent keyword classifier — swap for the intelligence LLM in prod. */
    private static String score(String text) {
        String t = text == null ? "" : text.toLowerCase();
        boolean pos = POSITIVE.stream().anyMatch(t::contains);
        boolean neg = NEGATIVE.stream().anyMatch(t::contains);
        if (pos && !neg) {
            return "positive";
        }
        if (neg && !pos) {
            return "negative";
        }
        return "neutral";
    }

    private static boolean isSupport(String text) {
        String t = text == null ? "" : text.toLowerCase();
        return SUPPORT.stream().anyMatch(t::contains);
    }
}
