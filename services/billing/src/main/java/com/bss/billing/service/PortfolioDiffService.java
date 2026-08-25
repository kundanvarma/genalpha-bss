package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SC-P3 — the shadow-operator clone's ANSWER: price BOTH portfolios with the
 * same engine that cuts real bills and report the difference by name. Matched
 * offerings carry their per-month delta; only-in-A / only-in-B are named; the
 * portfolio monthly totals sit at the bottom. Read-only, assumptions on its
 * face — mutate the clone, read the diff, decide.
 */
@Service
public class PortfolioDiffService {

    private final BillingRunService runService;
    private final DownstreamClients.CatalogClient catalog;

    public PortfolioDiffService(BillingRunService runService, DownstreamClients.CatalogClient catalog) {
        this.runService = runService;
        this.catalog = catalog;
    }

    private Map<String, BigDecimal> monthlyByName(String tenantId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            for (Map<String, Object> o : catalog.allOfferings()) {
                String name = String.valueOf(o.get("name"));
                String id = String.valueOf(o.get("id"));
                try {
                    BigDecimal monthly = runService.monthlyFor(id, new TreeMap<>(), unitCache);
                    if (monthly != null && monthly.signum() > 0) {
                        out.putIfAbsent(name, monthly);
                    }
                } catch (RuntimeException e) {
                    // an unpriceable offering is absent, not a crash
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diff(String tenantA, String tenantB) {
        Map<String, BigDecimal> a = monthlyByName(tenantA);
        Map<String, BigDecimal> b = monthlyByName(tenantB);
        List<Map<String, Object>> changed = new ArrayList<>();
        List<String> onlyInA = new ArrayList<>();
        List<String> onlyInB = new ArrayList<>();
        BigDecimal totalA = BigDecimal.ZERO;
        BigDecimal totalB = BigDecimal.ZERO;
        int matched = 0;
        for (Map.Entry<String, BigDecimal> e : a.entrySet()) {
            totalA = totalA.add(e.getValue());
            BigDecimal other = b.get(e.getKey());
            if (other == null) {
                onlyInA.add(e.getKey());
                continue;
            }
            matched++;
            if (other.compareTo(e.getValue()) != 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", e.getKey());
                row.put("monthlyA", e.getValue());
                row.put("monthlyB", other);
                row.put("delta", other.subtract(e.getValue()));
                changed.add(row);
            }
        }
        for (Map.Entry<String, BigDecimal> e : b.entrySet()) {
            totalB = totalB.add(e.getValue());
            if (!a.containsKey(e.getKey())) {
                onlyInB.add(e.getKey());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "PortfolioDiff");
        out.put("tenantA", tenantA);
        out.put("tenantB", tenantB);
        out.put("matched", matched);
        out.put("changed", changed);
        out.put("onlyInA", onlyInA);
        out.put("onlyInB", onlyInB);
        out.put("portfolioMonthlyA", totalA);
        out.put("portfolioMonthlyB", totalB);
        out.put("portfolioDelta", totalB.subtract(totalA));
        out.put("assumptions", List.of(
                "priced by the SAME engine that cuts real bills (base recurring, no characteristics)",
                "matched by offering NAME; unpriceable offerings are absent, not zero",
                "read-only: nothing was billed or changed"));
        return out;
    }
}
