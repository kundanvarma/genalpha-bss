package com.bss.intelligence.signal;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.events.DomainEventPublisher;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLTV the structural way (SI-P4): the number a bolt-on listening tool must
 * guess, computed from data this BSS actually holds — real billed amounts and
 * the churn book. NO black box: the formula is
 *   cltv = avgMonthlyBilled × expectedMonths (churn-alerted ? 9 : 24)
 * and every event carries its inputs, so the number can always be re-derived.
 * Lands as a NUMERIC trait via CltvScoredEvent → the trait listener, making
 * "worth ≥ 5000" an audience leaf.
 */
@Service
public class CltvScorer {

    private static final Logger log = LoggerFactory.getLogger(CltvScorer.class);
    private static final int MONTHS_AT_RISK = 9;
    private static final int MONTHS_STEADY = 24;

    private final BssApiClient bss;
    private final ChurnAlertRepository churnAlerts;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;

    public CltvScorer(BssApiClient bss, ChurnAlertRepository churnAlerts,
            DomainEventPublisher events, TenantRegistry tenants) {
        this.bss = bss;
        this.churnAlerts = churnAlerts;
        this.events = events;
        this.tenants = tenants;
    }

    @Scheduled(initialDelayString = "${bss.intelligence.cltv.initial-delay-ms:240000}",
            fixedDelayString = "${bss.intelligence.cltv.fixed-delay-ms:3600000}")
    public void scheduledSweep() {
        sweepAllTenants();
    }

    public Map<String, Object> sweepAllTenants() {
        int scored = 0;
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                scored += sweepCurrentTenant();
            } catch (Exception e) {
                log.warn("cltv sweep skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
        return Map.of("scored", scored);
    }

    public int sweepCurrentTenant() {
        String tenant = TenantContext.current();
        Set<String> customers = new LinkedHashSet<>();
        for (Map<String, Object> agreement : bss.activeAgreements()) {
            String party = engagedParty(agreement.get("engagedParty"));
            if (party != null) {
                customers.add(party);
            }
        }
        int scored = 0;
        for (String party : customers) {
            List<Map<String, Object>> bills = bss.billsOf(party);
            if (bills.isEmpty()) {
                continue;
            }
            BigDecimal total = BigDecimal.ZERO;
            int counted = 0;
            for (Map<String, Object> bill : bills) {
                BigDecimal amount = amountOf(bill);
                if (amount != null) {
                    total = total.add(amount);
                    counted++;
                }
            }
            if (counted == 0) {
                continue;
            }
            BigDecimal avgMonthly = total.divide(BigDecimal.valueOf(counted), 2, RoundingMode.HALF_UP);
            boolean atRisk = churnAlerts.existsByTenantIdAndPartyId(tenant, party);
            int months = atRisk ? MONTHS_AT_RISK : MONTHS_STEADY;
            BigDecimal cltv = avgMonthly.multiply(BigDecimal.valueOf(months))
                    .setScale(0, RoundingMode.HALF_UP);

            // the event CARRIES its inputs — the number is always re-derivable
            Map<String, Object> score = new LinkedHashMap<>();
            score.put("partyId", party);
            score.put("cltv", cltv);
            score.put("avgMonthlyBilled", avgMonthly);
            score.put("billsCounted", counted);
            score.put("expectedMonths", months);
            score.put("churnAlerted", atRisk);
            score.put("formula", "cltv = avgMonthlyBilled × expectedMonths (churn-alerted ? "
                    + MONTHS_AT_RISK + " : " + MONTHS_STEADY + ")");
            events.publish("CltvScoredEvent", "cltvScore", score, tenant);
            scored++;
        }
        return scored;
    }

    @SuppressWarnings("unchecked")
    private static String engagedParty(Object engaged) {
        if (engaged instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m && m.get("id") != null) {
                    return String.valueOf(m.get("id"));
                }
            }
        }
        return null;
    }

    private static BigDecimal amountOf(Map<String, Object> bill) {
        for (String key : new String[] {"taxIncludedAmount", "amountDue", "remainingAmount"}) {
            if (bill.get(key) instanceof Map<?, ?> m && m.get("value") != null) {
                try {
                    return new BigDecimal(String.valueOf(m.get("value")));
                } catch (NumberFormatException e) {
                    // fall through to the next key
                }
            }
        }
        return null;
    }
}
