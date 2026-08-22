package com.bss.intelligence.fairplay;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.events.DomainEventPublisher;
import com.bss.intelligence.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G2 — THE RIGHT-PLAN GUARANTEE: the honest-machine move nobody in the
 * industry dares. The meters already know who is on the WRONG plan; this
 * sweep finds customers using a fraction of their allowance and suggests the
 * CHEAPER plan that still fits — published as a count ("plans right-sized
 * this month"), delivered as events a journey can turn into a message. The
 * short-term ARPU dip is the price of a trust loop whose churn lift the
 * holdout machinery can prove.
 */
@Service
public class FairPlayService {

    private static final Logger log = LoggerFactory.getLogger(FairPlayService.class);

    private final BssApiClient bss;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final BigDecimal usageShare;
    private final int partyCap;

    public FairPlayService(BssApiClient bss, DomainEventPublisher events, TenantScope tenantScope,
            @Value("${bss.intelligence.fairplay-usage-share:0.30}") BigDecimal usageShare,
            @Value("${bss.intelligence.fairplay-party-cap:200}") int partyCap) {
        this.bss = bss;
        this.events = events;
        this.tenantScope = tenantScope;
        this.usageShare = usageShare;
        this.partyCap = partyCap;
    }

    public Map<String, Object> sweep() {
        return sweep(null);
    }

    /** Targeted (partyId) or capped whole-base pass — the shadow-billing lesson. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sweep(String onlyPartyId) {
        String tenant = tenantScope.currentTenantId();

        // catalog: monthly price per offering + allowance GB per offering
        Map<String, BigDecimal> priceByOffering = new HashMap<>();
        Map<String, Map<String, Object>> priceById = new HashMap<>();
        for (Map<String, Object> p : bss.offeringPrices()) {
            priceById.put(String.valueOf(p.get("id")), p);
        }
        List<Map<String, Object>> offerings = bss.offerings();
        for (Map<String, Object> o : offerings) {
            for (Object ref : o.get("productOfferingPrice") instanceof List<?> l ? l : List.of()) {
                if (ref instanceof Map<?, ?> r) {
                    Map<String, Object> price = priceById.get(String.valueOf(r.get("id")));
                    if (price != null && "recurring".equals(price.get("priceType"))
                            && price.get("price") instanceof Map<?, ?> money && money.get("value") != null) {
                        priceByOffering.put(String.valueOf(o.get("name")),
                                new BigDecimal(String.valueOf(money.get("value"))));
                    }
                }
            }
        }
        Map<String, BigDecimal> allowanceByOfferingId = new HashMap<>();
        Map<String, String> usageTypeByOfferingId = new HashMap<>();
        for (Map<String, Object> a : bss.usageAllowances()) {
            if (a.get("productOffering") instanceof Map<?, ?> po && a.get("allowance") instanceof Map<?, ?> al) {
                allowanceByOfferingId.put(String.valueOf(po.get("id")),
                        new BigDecimal(String.valueOf(al.get("value"))));
                usageTypeByOfferingId.put(String.valueOf(po.get("id")),
                        String.valueOf(a.get("usageType")));
            }
        }
        Map<String, String> offeringIdByName = new HashMap<>();
        Map<String, String> categoryByName = new HashMap<>();
        for (Map<String, Object> o : offerings) {
            offeringIdByName.put(String.valueOf(o.get("name")), String.valueOf(o.get("id")));
            if (o.get("category") instanceof List<?> cats && !cats.isEmpty()
                    && cats.get(0) instanceof Map<?, ?> c0) {
                categoryByName.put(String.valueOf(o.get("name")), String.valueOf(c0.get("name")));
            }
        }

        // the base: owner -> offering names (only allowance-bearing plans matter)
        Map<String, List<String>> plansByOwner = new LinkedHashMap<>();
        for (Map<String, Object> product : bss.allActiveProducts()) {
            if (!(product.get("productOffering") instanceof Map<?, ?> ref)) {
                continue;
            }
            String offeringId = String.valueOf(ref.get("id"));
            if (!allowanceByOfferingId.containsKey(offeringId)) {
                continue;
            }
            for (Object rp : product.get("relatedParty") instanceof List<?> l ? l : List.of()) {
                if (rp instanceof Map<?, ?> pm && pm.get("id") != null) {
                    plansByOwner.computeIfAbsent(String.valueOf(pm.get("id")), k -> new ArrayList<>())
                            .add(String.valueOf(ref.get("name")));
                }
            }
        }

        if (onlyPartyId != null) {
            plansByOwner.keySet().retainAll(List.of(onlyPartyId));
        }
        List<Map<String, Object>> suggestions = new ArrayList<>();
        int seen = 0;
        for (Map.Entry<String, List<String>> owner : plansByOwner.entrySet()) {
            if (++seen > partyCap) {
                log.info("fair play: capped at {} of {} plan owners this sweep",
                        partyCap, plansByOwner.size());
                break;
            }
            BigDecimal used = usedGbOf(owner.getKey());
            for (String planName : owner.getValue()) {
                String offeringId = offeringIdByName.get(planName);
                BigDecimal allowance = offeringId == null ? null : allowanceByOfferingId.get(offeringId);
                BigDecimal price = priceByOffering.get(planName);
                if (allowance == null || price == null || allowance.signum() <= 0) {
                    continue;
                }
                if (used.compareTo(allowance.multiply(usageShare)) >= 0) {
                    continue;   // they use their plan — nothing to right-size
                }
                // the cheaper plan that still fits with headroom (1.5x current use)
                Map<String, Object> best = null;
                BigDecimal bestPrice = price;
                String category = categoryByName.get(planName);
                for (Map.Entry<String, String> cand : offeringIdByName.entrySet()) {
                    BigDecimal candAllowance = allowanceByOfferingId.get(cand.getValue());
                    BigDecimal candPrice = priceByOffering.get(cand.getKey());
                    if (candAllowance == null || candPrice == null) {
                        continue;
                    }
                    // a downgrade stays in the SAME line of business — a bundle
                    // is never the honest answer to an oversized mobile plan
                    if (!java.util.Objects.equals(category, categoryByName.get(cand.getKey()))) {
                        continue;
                    }
                    if (candPrice.compareTo(bestPrice) < 0
                            && candAllowance.compareTo(allowance) < 0
                            && candAllowance.compareTo(used.multiply(new BigDecimal("1.5"))) >= 0) {
                        best = Map.of("offeringName", cand.getKey(), "monthlyPrice", candPrice,
                                "allowanceGb", candAllowance);
                        bestPrice = candPrice;
                    }
                }
                if (best != null) {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    suggestion.put("partyId", owner.getKey());
                    suggestion.put("currentOffering", planName);
                    suggestion.put("currentMonthly", price);
                    suggestion.put("usedGb", used);
                    suggestion.put("allowanceGb", allowance);
                    suggestion.put("suggested", best);
                    suggestion.put("monthlySaving", price.subtract(bestPrice));
                    suggestions.add(suggestion);
                    events.publish("RightPlanSuggestedEvent", "rightPlan", suggestion, tenant);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "FairPlaySweep");
        out.put("suggestions", suggestions);
        out.put("rightSized", suggestions.size());
        out.put("assumptions", List.of(
                "a plan is oversized when this month's use is under "
                        + usageShare.movePointRight(2) + "% of its allowance",
                "suggestions keep 1.5x the current use as headroom — nobody is squeezed",
                "read-only: the customer decides; the journey only tells them"));
        return out;
    }

    private BigDecimal usedGbOf(String partyId) {
        BigDecimal max = BigDecimal.ZERO;
        for (Map<String, Object> meter : bss.usageMeters(partyId)) {
            BigDecimal v = deepNumber(meter, "usedValue");
            if (v != null && v.compareTo(max) > 0) {
                max = v;
            }
        }
        return max;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal deepNumber(Object node, String key) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (key.equals(e.getKey()) && e.getValue() != null) {
                    try {
                        return new BigDecimal(String.valueOf(e.getValue()));
                    } catch (NumberFormatException ignored) {
                        // not numeric — keep walking
                    }
                }
                BigDecimal found = deepNumber(e.getValue(), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                BigDecimal found = deepNumber(o, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
