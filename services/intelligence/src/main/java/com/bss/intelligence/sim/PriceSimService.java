package com.bss.intelligence.sim;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P1 of the commercial simulator: SIMULATE THE MONEY BEFORE YOU MOVE IT.
 * A proposed price change is replayed against the REAL subscriber base
 * (inventory), the REAL catalog and the REAL wholesale rate card — no model
 * of the system, the system's own data run forward. The house honesty rules
 * apply: every report carries its assumptions on its face, names its data
 * basis, and nothing here mutates production state — the report is the only
 * thing written.
 */
@Service
public class PriceSimService {

    private final BssApiClient bss;
    private final ChurnAlertRepository churnAlerts;
    private final PriceSimReportRepository reports;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public PriceSimService(BssApiClient bss, ChurnAlertRepository churnAlerts,
            PriceSimReportRepository reports, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.bss = bss;
        this.churnAlerts = churnAlerts;
        this.reports = reports;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PriceSimReportView simulate(PriceSimRequest request) {
        List<PriceSimRequest.PriceChange> rawChanges = request.changes();
        if (rawChanges == null || rawChanges.isEmpty()) {
            throw new BadRequestException("changes [{offeringName, newMonthlyPrice}] are required");
        }
        String tenant = tenantScope.currentTenantId();
        BigDecimal churnPct = request.assumedChurnPct();
        if (churnPct != null && (churnPct.signum() < 0 || churnPct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BadRequestException("assumedChurnPct must be 0-100");
        }

        // ---- the raw material: base, catalog, prices, cost side ----
        List<Map<String, Object>> products = bss.allActiveProducts();
        // THE FLYWHEEL, v1: when no churn assumption is supplied, the sim's
        // prior is MEASURED — lifetime terminations over the whole base.
        boolean measuredPrior = false;
        if (churnPct == null) {
            int terminated = bss.terminatedCountsByOffering().values().stream()
                    .mapToInt(Integer::intValue).sum();
            int base = products.size() + terminated;
            if (terminated > 0 && base > 0) {
                churnPct = new BigDecimal(terminated * 100.0 / base)
                        .setScale(1, RoundingMode.HALF_UP);
                measuredPrior = true;
            }
        }
        List<Map<String, Object>> offerings = bss.offerings();
        Map<String, Map<String, Object>> priceById = new HashMap<>();
        for (Map<String, Object> p : bss.offeringPrices()) {
            priceById.put(String.valueOf(p.get("id")), p);
        }
        Map<String, BigDecimal> allowanceGb = new HashMap<>();   // offeringId -> GB
        for (Map<String, Object> a : bss.usageAllowances()) {
            if (a.get("productOffering") instanceof Map<?, ?> po && a.get("allowance") instanceof Map<?, ?> al) {
                allowanceGb.put(String.valueOf(po.get("id")), num(al.get("value")));
            }
        }
        BigDecimal dataRate = null;                              // wholesale NOK-or-EUR per GB
        String costBasis = "no wholesale rate card — margin not computed";
        for (Map<String, Object> c : bss.wholesaleRateCards()) {
            if ("GB".equalsIgnoreCase(String.valueOf(c.get("unit")))) {
                dataRate = num(c.get("wholesaleRate"));
                costBasis = "wholesale data rate " + dataRate + " " + c.get("currency")
                        + "/GB x plan allowance = cost CEILING (full-allowance burn)";
                break;
            }
        }

        // subscriber count + owner parties per offering name
        Map<String, Integer> subsByOffering = new HashMap<>();
        Map<String, Set<String>> ownersByOffering = new HashMap<>();
        for (Map<String, Object> product : products) {
            if (!(product.get("productOffering") instanceof Map<?, ?> ref)) {
                continue;
            }
            String name = String.valueOf(ref.get("name"));
            subsByOffering.merge(name, 1, Integer::sum);
            for (Object rp : product.get("relatedParty") instanceof List<?> l ? l : List.of()) {
                if (rp instanceof Map<?, ?> m && m.get("id") != null) {
                    ownersByOffering.computeIfAbsent(name, k -> new HashSet<>())
                            .add(String.valueOf(m.get("id")));
                }
            }
        }

        // ---- the simulation: mechanical first, assumption-labeled second ----
        List<PriceSimReportView.Line> lines = new ArrayList<>();
        BigDecimal totalAnnualDelta = BigDecimal.ZERO;
        String currency = null;
        int totalChurnRisk = 0;
        for (PriceSimRequest.PriceChange change : rawChanges) {
            String name = String.valueOf(change.offeringName());
            BigDecimal proposed = change.newMonthlyPrice();
            if (proposed == null || proposed.signum() < 0) {
                throw new BadRequestException("newMonthlyPrice is required per change");
            }
            Map<String, Object> offering = offerings.stream()
                    .filter(o -> name.equals(o.get("name"))).findFirst()
                    .orElseThrow(() -> new BadRequestException("offering '" + name + "' not found"));
            BigDecimal current = monthlyPriceOf(offering, priceById);
            if (current == null) {
                throw new BadRequestException("offering '" + name + "' has no recurring monthly price");
            }
            currency = currency != null ? currency : currencyOf(offering, priceById);
            int subs = subsByOffering.getOrDefault(name, 0);
            BigDecimal monthlyDelta = proposed.subtract(current).multiply(BigDecimal.valueOf(subs));
            BigDecimal annualDelta = monthlyDelta.multiply(BigDecimal.valueOf(12));
            totalAnnualDelta = totalAnnualDelta.add(annualDelta);

            Set<String> owners = ownersByOffering.getOrDefault(name, Set.of());
            int churnRisk = (int) churnAlerts.findAll().stream()
                    .filter(a -> tenant.equals(a.getTenantId()) && owners.contains(a.getPartyId()))
                    .map(a -> a.getPartyId()).distinct().count();
            totalChurnRisk += churnRisk;

            BigDecimal gb = allowanceGb.get(String.valueOf(offering.get("id")));
            BigDecimal cost = dataRate != null && gb != null ? gb.multiply(dataRate) : null;
            BigDecimal churnedAnnual = null;
            if (churnPct != null && proposed.compareTo(current) > 0) {
                BigDecimal keep = BigDecimal.ONE.subtract(churnPct.movePointLeft(2));
                churnedAnnual = proposed.multiply(BigDecimal.valueOf(subs)).multiply(keep)
                        .subtract(current.multiply(BigDecimal.valueOf(subs)))
                        .multiply(BigDecimal.valueOf(12));
            }
            lines.add(new PriceSimReportView.Line(name, subs, current, proposed,
                    monthlyDelta.setScale(2, RoundingMode.HALF_UP),
                    annualDelta.setScale(2, RoundingMode.HALF_UP),
                    cost == null ? null : cost.setScale(2, RoundingMode.HALF_UP),
                    cost == null ? null : current.subtract(cost).setScale(2, RoundingMode.HALF_UP),
                    cost == null ? null : proposed.subtract(cost).setScale(2, RoundingMode.HALF_UP),
                    churnRisk,
                    churnedAnnual == null ? null : churnedAnnual.setScale(2, RoundingMode.HALF_UP)));
        }

        // ---- honesty on the face: assumptions + basis, always ----
        List<String> assumptions = new ArrayList<>();
        assumptions.add(churnPct == null
                ? "NO elasticity assumed — revenue delta is mechanical (every subscriber stays)"
                : (measuredPrior
                        ? "elasticity prior: " + churnPct + "% is the MEASURED churn baseline "
                                + "(lifetime terminations over base) — a floor, not a price response; "
                                + "pass churnPct to override"
                        : "assumed " + churnPct + "% of each raised plan's base churns (flat, user-supplied)"));
        assumptions.add(costBasis);
        assumptions.add("base = active inventory at simulation time; usage-priced and one-time components unchanged");

        PriceSimReportView report = new PriceSimReportView("PriceChangeSimulation", lines,
                totalAnnualDelta.setScale(2, RoundingMode.HALF_UP), currency, totalChurnRisk, assumptions,
                new PriceSimReportView.Basis(products.size(), offerings.size(), OffsetDateTime.now().toString()),
                null, null);

        // the ONLY write this simulation performs: its own receipt
        String name = request.name() != null ? request.name()
                : lines.get(0).offeringName() + " → " + rawChanges.get(0).newMonthlyPrice();
        Receipt receipt = saveReport(name, toJson(request), report);
        return report.saved(receipt.id(), receipt.name());
    }

    @Transactional(readOnly = true)
    public List<SavedReport> list() {
        return list("PriceChangeSimulation");
    }

    /** Saved reports of one kind — the price pane and the prospect pane each
     *  read their own shelf of receipts. */
    @Transactional(readOnly = true)
    public List<SavedReport> list(String type) {
        return listAll().stream()
                .filter(m -> m.report() != null && type.equals(m.report().path("@type").asText(null)))
                .toList();
    }

    /** The saved receipt's id and name — what a simulator appends to its report. */
    public record Receipt(String id, String name) {
    }

    /** Persist any simulator's report as a receipt on the shared shelf. */
    @Transactional
    public Receipt saveReport(String name, String requestJson, Object report) {
        PriceSimReport row = new PriceSimReport();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setName(name);
        row.setRequestJson(requestJson);
        row.setReportJson(toJson(report));
        row.setCreatedAt(OffsetDateTime.now());
        reports.save(row);
        return new Receipt(row.getId(), row.getName());
    }

    @Transactional(readOnly = true)
    private List<SavedReport> listAll() {
        List<SavedReport> out = new ArrayList<>();
        for (PriceSimReport r : reports.findTop50ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())) {
            try {
                out.add(SavedReport.of(r, objectMapper.readTree(r.getReportJson())));
            } catch (Exception ignored) {
                // an unreadable stored report still lists by name
                out.add(SavedReport.unreadable(r));
            }
        }
        return out;
    }

    private BigDecimal monthlyPriceOf(Map<String, Object> offering, Map<String, Map<String, Object>> priceById) {
        for (Object ref : offering.get("productOfferingPrice") instanceof List<?> l ? l : List.of()) {
            if (!(ref instanceof Map<?, ?> r)) {
                continue;
            }
            Map<String, Object> price = priceById.get(String.valueOf(r.get("id")));
            if (price != null && "recurring".equals(price.get("priceType"))
                    && price.get("price") instanceof Map<?, ?> p) {
                return num(p.get("value"));
            }
        }
        return null;
    }

    private String currencyOf(Map<String, Object> offering, Map<String, Map<String, Object>> priceById) {
        for (Object ref : offering.get("productOfferingPrice") instanceof List<?> l ? l : List.of()) {
            if (ref instanceof Map<?, ?> r) {
                Map<String, Object> price = priceById.get(String.valueOf(r.get("id")));
                if (price != null && price.get("price") instanceof Map<?, ?> p && p.get("unit") != null) {
                    return String.valueOf(p.get("unit"));
                }
            }
        }
        return null;
    }

    private static BigDecimal num(Object o) {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }

    private String toJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new BadRequestException("not serialisable");
        }
    }
}
