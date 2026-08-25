package com.bss.intelligence.service;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REGULATORY REHEARSAL: in right-to-exit markets a price rise opens an exit
 * window — rehearse it BEFORE the letters go out. The cohort is every
 * holder of the offering (that IS the notification list); the port-out
 * exposure is the cohort members already carrying an open churn-risk alert.
 * Scores are the model's, not fate — the assumptions say so.
 */
@Service
public class PriceRiseRehearsalService {

    private final BssApiClient bss;
    private final ChurnAlertRepository churn;
    private final TenantScope tenantScope;

    public PriceRiseRehearsalService(BssApiClient bss, ChurnAlertRepository churn, TenantScope tenantScope) {
        this.bss = bss;
        this.churn = churn;
        this.tenantScope = tenantScope;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> rehearse(String offeringName, BigDecimal percent) {
        String tenant = tenantScope.currentTenantId();
        Set<String> cohort = new HashSet<>();
        for (Map<String, Object> p : bss.allActiveProducts()) {
            Object ref = p.get("productOffering");
            if (!(ref instanceof Map<?, ?> r) || !offeringName.equals(String.valueOf(r.get("name")))) {
                continue;
            }
            for (Map<String, Object> rp : (List<Map<String, Object>>) p.getOrDefault("relatedParty", List.of())) {
                if ("customer".equalsIgnoreCase(String.valueOf(rp.get("role")))) {
                    cohort.add(String.valueOf(rp.get("id")));
                }
            }
        }
        int atRisk = 0;
        for (String partyId : cohort) {
            if (churn.existsByTenantIdAndPartyId(tenant, partyId)) {
                atRisk++;
            }
        }
        // the current monthly, from the catalog the shop actually sells
        BigDecimal monthly = BigDecimal.ZERO;
        for (Map<String, Object> o : bss.offerings()) {
            if (!offeringName.equals(String.valueOf(o.get("name")))) {
                continue;
            }
            for (Map<String, Object> pr : (List<Map<String, Object>>) o.getOrDefault("productOfferingPrice", List.of())) {
                Map<String, Object> full = null;
                for (Map<String, Object> cand : bss.offeringPrices()) {
                    if (String.valueOf(cand.get("id")).equals(String.valueOf(pr.get("id")))) {
                        full = cand;
                        break;
                    }
                }
                if (full != null && "recurring".equalsIgnoreCase(String.valueOf(full.get("priceType")))
                        && full.get("price") instanceof Map<?, ?> money && money.get("value") != null) {
                    monthly = monthly.add(new BigDecimal(String.valueOf(money.get("value"))));
                }
            }
            break;
        }
        BigDecimal delta = monthly.multiply(percent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal newMonthly = monthly.add(delta);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "PriceRiseRehearsal");
        out.put("offeringName", offeringName);
        out.put("risePercent", percent);
        out.put("currentMonthly", monthly);
        out.put("newMonthly", newMonthly);
        out.put("notificationLetters", cohort.size());
        out.put("monthlyUpsideIfNobodyLeaves", delta.multiply(new BigDecimal(cohort.size())));
        out.put("portOutExposureCustomers", atRisk);
        out.put("annualRevenueAtRisk", newMonthly.multiply(new BigDecimal(atRisk))
                .multiply(new BigDecimal(12)).setScale(2, RoundingMode.HALF_UP));
        out.put("assumptions", List.of(
                "the cohort IS the notification list — every holder gets the letter",
                "port-out exposure = cohort members with an OPEN churn-risk alert; a score is the model's opinion, not fate",
                "base monthly from the live catalog's recurring components; characteristics not applied",
                "read-only: no price changed, no letter sent"));
        return out;
    }
}
