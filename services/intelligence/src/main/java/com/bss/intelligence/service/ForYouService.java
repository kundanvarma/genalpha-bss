package com.bss.intelligence.service;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE INDIVIDUALIZED SHOP: the signed-in customer's own "For you" rail.
 * NBO generalized from one-offer-for-the-agent to a rail-for-me — and
 * SELF-scoped: the party is always the caller's own subject, never a
 * parameter, so a customer can only individualize their own shop.
 *
 * Receipts before advice, as always: the TMF680 ranking (already fused
 * with consented insight interests) IS the personalization arithmetic;
 * an open churn-risk alert adds a retention flag (keeping a customer is
 * personalization too); and the model's only job is the CAPTION — one
 * warm sentence grounding the rail in the customer's own interests and
 * holdings, through the governed door (metered, budgeted, killable),
 * failing open to no caption. A short per-party cache keeps a browsing
 * session from burning AI budget on every page view.
 */
@Service
public class ForYouService {

    private static final int RAIL_SIZE = 4;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final BssApiClient bss;
    private final ChurnAlertRepository churnAlerts;
    private final AiGovernor governor;
    private final TenantScope tenantScope;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    record CacheEntry(long at, Map<String, Object> value) {
    }

    public ForYouService(BssApiClient bss, ChurnAlertRepository churnAlerts,
            AiGovernor governor, TenantScope tenantScope) {
        this.bss = bss;
        this.churnAlerts = churnAlerts;
        this.governor = governor;
        this.tenantScope = tenantScope;
    }

    public Map<String, Object> forParty(String partyId) {
        String tenant = tenantScope.currentTenantId();
        String key = tenant + ":" + partyId;
        CacheEntry cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            Map<String, Object> copy = new LinkedHashMap<>(cached.value());
            copy.put("cached", true);
            return copy;
        }

        List<Map<String, Object>> candidates = bss.recommendationItems(partyId);
        List<String> interests = bss.interestsOf(partyId); // consent-gated at the source
        List<String> holdings = bss.holdingsOf(partyId).stream()
                .map(p -> String.valueOf(p.get("name"))).limit(6).toList();
        boolean retention = churnAlerts.existsByTenantIdAndPartyId(tenant, partyId);

        List<Map<String, Object>> rail = new ArrayList<>();
        for (Map<String, Object> item : candidates) {
            if (item.get("offering") instanceof Map<?, ?> off && off.get("id") != null) {
                rail.add(Map.of("id", String.valueOf(off.get("id")),
                        "name", String.valueOf(off.get("name"))));
                if (rail.size() == RAIL_SIZE) {
                    break;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rail);
        out.put("interests", interests);
        out.put("retentionFlag", retention);
        out.put("caption", rail.isEmpty() ? null
                : caption(rail, interests, holdings, retention));
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("cached", false);
        cache.put(key, new CacheEntry(System.currentTimeMillis(), out));
        return out;
    }

    /** One governed FAST call for one warm sentence — or silence. */
    private String caption(List<Map<String, Object>> rail, List<String> interests,
            List<String> holdings, boolean retention) {
        try {
            StringBuilder user = new StringBuilder();
            rail.forEach(r -> user.append("OFFER: ").append(r.get("name")).append('\n'));
            interests.forEach(i -> user.append("INTEREST: ").append(i).append('\n'));
            holdings.forEach(h -> user.append("HOLDING: ").append(h).append('\n'));
            if (retention) {
                user.append("RETENTION: this customer is at churn risk\n");
            }
            String raw = governor.complete("for-you-caption", LlmAdapter.Tier.FAST,
                    "You write the one-line caption of a personalized shop rail for a telecom"
                            + " customer. Warm, specific, grounded ONLY in the INTEREST and"
                            + " HOLDING lines — never invent facts, never mention churn."
                            + " Respond with ONLY one labeled line:\n"
                            + "CAPTION: <max 120 characters>",
                    user.toString());
            for (String line : raw.split("\\R")) {
                String t = line.trim().replaceFirst("^[*#>\\-\\s]+", "");
                if (t.regionMatches(true, 0, "CAPTION:", 0, 8)) {
                    String value = t.substring(8).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
            return null;
        } catch (RuntimeException refusedOrDown) {
            return null; // budget/kill-switch/model trouble: the rail stands alone
        }
    }
}
