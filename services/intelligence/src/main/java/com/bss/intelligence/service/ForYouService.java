package com.bss.intelligence.service;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Service;

import com.bss.intelligence.service.ForYouRail.Upsell;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static com.bss.intelligence.api.Wire.idOf;

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
    private final com.bss.intelligence.llm.TenantVoice voice;

    record CacheEntry(long at, ForYouRail value) {
    }

    public ForYouService(BssApiClient bss, ChurnAlertRepository churnAlerts,
            AiGovernor governor, TenantScope tenantScope,
            com.bss.intelligence.llm.TenantVoice voice) {
        this.bss = bss;
        this.churnAlerts = churnAlerts;
        this.governor = governor;
        this.tenantScope = tenantScope;
        this.voice = voice;
    }

    public ForYouRail forParty(String partyId) {
        String tenant = tenantScope.currentTenantId();
        String key = tenant + ":" + partyId;
        CacheEntry cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached.value().cached(true);
        }

        List<Map<String, Object>> candidates = bss.recommendationItems(partyId);
        List<String> interests = bss.interestsOf(partyId); // consent-gated at the source
        List<String> holdings = bss.holdingsOf(partyId).stream()
                .map(p -> String.valueOf(p.get("name"))).limit(6).toList();
        boolean retention = churnAlerts.existsByTenantIdAndPartyId(tenant, partyId);

        List<OfferRef> rail = new ArrayList<>();
        for (Map<String, Object> item : candidates) {
            String offeringId = idOf(item.get("offering"));
            if (offeringId != null && item.get("offering") instanceof Map<?, ?> off) {
                rail.add(new OfferRef(offeringId, String.valueOf(off.get("name"))));
                if (rail.size() == RAIL_SIZE) {
                    break;
                }
            }
        }

        ForYouRail out = new ForYouRail(rail, interests, retention, upsellOf(partyId),
                rail.isEmpty() ? null : caption(rail, interests, holdings, retention),
                OffsetDateTime.now().toString(), false);
        cache.put(key, new CacheEntry(System.currentTimeMillis(), out));
        return out;
    }


    /**
     * The usage-aware upsell: when a meter is nearly drained (>= 80%),
     * suggest the CHEAPEST-STEP fuller plan — the offering with the
     * next-larger allowance for the SAME usage type, from the allowance
     * ladder the fleet always had as data. No meter, no fuller rung, or a
     * comfortable meter: no block. Never an invented deal — just the next
     * rung, named.
     */
    private Upsell upsellOf(String partyId) {
        List<Map<String, Object>> meters = bss.usageMeters(partyId);
        Map<String, Object> tightest = null;
        double tightestPct = 0;
        for (Map<String, Object> m : meters) {
            double allowed = numberOf(m.get("allowedValue"));
            double used = numberOf(m.get("usedValue"));
            if (allowed <= 0) {
                continue;
            }
            double pct = used / allowed;
            if (pct >= 0.8 && pct > tightestPct) {
                tightest = m;
                tightestPct = pct;
            }
        }
        if (tightest == null) {
            return null;
        }
        String spec = String.valueOf(tightest.get("name"));
        double current = numberOf(tightest.get("allowedValue"));
        OfferRef nextRung = null;
        double nextValue = Double.MAX_VALUE;
        for (Map<String, Object> a : bss.usageAllowances()) {
            if (!spec.equals(String.valueOf(a.get("usageType")))) {
                continue;
            }
            double value = a.get("allowance") instanceof Map<?, ?> al
                    ? numberOf(al.get("value")) : 0;
            String rungId = idOf(a.get("productOffering"));
            if (value > current && value < nextValue && rungId != null
                    && a.get("productOffering") instanceof Map<?, ?> off) {
                nextValue = value;
                // some ladder rows carry no offering name — the card still
                // links to the real offering; the label stays descriptive
                Object name = off.get("name");
                String label = name == null || "null".equals(String.valueOf(name))
                        ? "the " + stripTrailingZeros(value) + " "
                                + String.valueOf(a.get("allowance") instanceof Map<?, ?> al2
                                        ? al2.get("units") : "") + " plan"
                        : String.valueOf(name);
                nextRung = new OfferRef(rungId, label);
            }
        }
        if (nextRung == null) {
            return null; // already on the top rung — nothing honest to suggest
        }
        return new Upsell(spec, Math.round(tightestPct * 100), tightest.get("usedValue"),
                tightest.get("allowedValue"), tightest.get("units"), nextRung, nextValue);
    }

    private static String stripTrailingZeros(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double numberOf(Object v) {
        try {
            return v == null ? 0 : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One governed FAST call for one warm sentence — or silence. */
    private String caption(List<OfferRef> rail, List<String> interests,
            List<String> holdings, boolean retention) {
        try {
            StringBuilder user = new StringBuilder();
            rail.forEach(r -> user.append("OFFER: ").append(r.name()).append('\n'));
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
                            + "CAPTION: <max 120 characters>" + voice.instruction(),
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
