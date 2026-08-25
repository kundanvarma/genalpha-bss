package com.bss.intelligence.sim;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CROSS-TENANT PRIORS: small operators lack the base to support their own
 * elasticity evidence — the fleet's AGGREGATE lends them one. Privacy is
 * structural: per-tenant numbers are computed inside each tenant's own
 * context and only the distribution (median/min/max over an unnamed set)
 * leaves; below the k-anonymity floor the benchmark refuses to exist.
 */
@Service
public class ElasticityBenchmarkService {

    private static final int K_ANONYMITY_FLOOR = 3;

    private final BssApiClient bss;
    private final TenantRegistry tenants;

    public ElasticityBenchmarkService(BssApiClient bss, TenantRegistry tenants) {
        this.bss = bss;
        this.tenants = tenants;
    }

    public Map<String, Object> benchmark() {
        List<Double> baselines = new ArrayList<>();
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                int gone = bss.terminatedCountsByOffering().values().stream()
                        .mapToInt(Integer::intValue).sum();
                int active = bss.allActiveProducts().size();
                int base = gone + active;
                if (base >= 10) {   // a tenant with no real base contributes noise, not evidence
                    baselines.add(gone * 100.0 / base);
                }
            } catch (RuntimeException e) {
                // an unreachable tenant simply doesn't contribute
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "ElasticityBenchmark");
        if (baselines.size() < K_ANONYMITY_FLOOR) {
            out.put("available", false);
            out.put("reason", "fewer than " + K_ANONYMITY_FLOOR
                    + " tenants contribute — below the k-anonymity floor the benchmark refuses to exist");
            return out;
        }
        Collections.sort(baselines);
        out.put("available", true);
        out.put("contributingTenants", baselines.size());
        out.put("medianChurnBaselinePct", round1(baselines.get(baselines.size() / 2)));
        out.put("minPct", round1(baselines.get(0)));
        out.put("maxPct", round1(baselines.get(baselines.size() - 1)));
        out.put("assumptions", List.of(
                "aggregate ONLY — no tenant is named, no per-tenant number leaves its own context",
                "k-anonymity floor: " + K_ANONYMITY_FLOOR + " contributing tenants minimum",
                "baseline = lifetime gone products over base, per tenant — a floor, not a price response"));
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
