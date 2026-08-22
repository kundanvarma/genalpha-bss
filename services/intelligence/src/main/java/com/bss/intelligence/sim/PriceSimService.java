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
import java.util.LinkedHashMap;
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> simulate(Map<String, Object> request) {
        if (!(request.get("changes") instanceof List<?> rawChanges) || rawChanges.isEmpty()) {
            throw new BadRequestException("changes [{offeringName, newMonthlyPrice}] are required");
        }
        String tenant = tenantScope.currentTenantId();
        BigDecimal churnPct = request.get("assumedChurnPct") == null ? null
                : new BigDecimal(String.valueOf(request.get("assumedChurnPct")));
        if (churnPct != null && (churnPct.signum() < 0 || churnPct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BadRequestException("assumedChurnPct must be 0-100");
        }

        // ---- the raw material: base, catalog, prices, cost side ----
        List<Map<String, Object>> products = bss.allActiveProducts();
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
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal totalAnnualDelta = BigDecimal.ZERO;
        String currency = null;
        int totalChurnRisk = 0;
        for (Object rawChange : rawChanges) {
            Map<String, Object> change = (Map<String, Object>) rawChange;
            String name = String.valueOf(change.get("offeringName"));
            BigDecimal proposed = num(change.get("newMonthlyPrice"));
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

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("offeringName", name);
            line.put("subscribers", subs);
            line.put("currentMonthly", current);
            line.put("proposedMonthly", proposed);
            line.put("monthlyRevenueDelta", monthlyDelta.setScale(2, RoundingMode.HALF_UP));
            line.put("annualRevenueDelta", annualDelta.setScale(2, RoundingMode.HALF_UP));
            BigDecimal gb = allowanceGb.get(String.valueOf(offering.get("id")));
            if (dataRate != null && gb != null) {
                BigDecimal cost = gb.multiply(dataRate);
                line.put("wholesaleCostCeilingPerSub", cost.setScale(2, RoundingMode.HALF_UP));
                line.put("marginPerSubBefore", current.subtract(cost).setScale(2, RoundingMode.HALF_UP));
                line.put("marginPerSubAfter", proposed.subtract(cost).setScale(2, RoundingMode.HALF_UP));
            }
            line.put("subscribersAtChurnRisk", churnRisk);
            if (churnPct != null && proposed.compareTo(current) > 0) {
                BigDecimal keep = BigDecimal.ONE.subtract(churnPct.movePointLeft(2));
                BigDecimal churnedAnnual = proposed.multiply(BigDecimal.valueOf(subs)).multiply(keep)
                        .subtract(current.multiply(BigDecimal.valueOf(subs)))
                        .multiply(BigDecimal.valueOf(12));
                line.put("annualRevenueDeltaWithAssumedChurn", churnedAnnual.setScale(2, RoundingMode.HALF_UP));
            }
            lines.add(line);
        }

        // ---- honesty on the face: assumptions + basis, always ----
        List<String> assumptions = new ArrayList<>();
        assumptions.add(churnPct == null
                ? "NO elasticity assumed — revenue delta is mechanical (every subscriber stays)"
                : "assumed " + churnPct + "% of each raised plan's base churns (flat, user-supplied)");
        assumptions.add(costBasis);
        assumptions.add("base = active inventory at simulation time; usage-priced and one-time components unchanged");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("@type", "PriceChangeSimulation");
        report.put("lines", lines);
        report.put("totalAnnualRevenueDelta", totalAnnualDelta.setScale(2, RoundingMode.HALF_UP));
        if (currency != null) {
            report.put("currency", currency);
        }
        report.put("subscribersAtChurnRisk", totalChurnRisk);
        report.put("assumptions", assumptions);
        report.put("basis", Map.of("activeProducts", products.size(),
                "offeringsInCatalog", offerings.size(), "asOf", OffsetDateTime.now().toString()));

        // the ONLY write this simulation performs: its own receipt
        PriceSimReport row = new PriceSimReport();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setName(String.valueOf(request.getOrDefault("name",
                lines.get(0).get("offeringName") + " → " + ((Map<String, Object>) rawChanges.get(0)).get("newMonthlyPrice"))));
        row.setRequestJson(toJson(request));
        row.setReportJson(toJson(report));
        row.setCreatedAt(OffsetDateTime.now());
        reports.save(row);
        report.put("id", row.getId());
        report.put("name", row.getName());
        return report;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return list("PriceChangeSimulation");
    }

    /** Saved reports of one kind — the price pane and the prospect pane each
     *  read their own shelf of receipts. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String type) {
        List<Map<String, Object>> all = listAll();
        return all.stream().filter(m -> {
            Object report = m.get("report");
            return report instanceof Map<?, ?> r && type.equals(r.get("@type"));
        }).toList();
    }

    /** Persist any simulator's report as a receipt on the shared shelf. */
    @Transactional
    public Map<String, Object> saveReport(String name, String requestJson, Map<String, Object> report) {
        PriceSimReport row = new PriceSimReport();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setName(name);
        row.setRequestJson(requestJson);
        row.setReportJson(toJson(report));
        row.setCreatedAt(OffsetDateTime.now());
        reports.save(row);
        report.put("id", row.getId());
        report.put("name", row.getName());
        return report;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceSimReport r : reports.findTop50ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("createdAt", r.getCreatedAt());
            try {
                Map<String, Object> report = objectMapper.readValue(r.getReportJson(), Map.class);
                m.put("totalAnnualRevenueDelta", report.get("totalAnnualRevenueDelta"));
                m.put("currency", report.get("currency"));
                m.put("subscribersAtChurnRisk", report.get("subscribersAtChurnRisk"));
                m.put("totalSubscribers", report.get("totalSubscribers"));
                m.put("annualRevenue", report.get("annualRevenue"));
                m.put("annualGrossMarginFloor", report.get("annualGrossMarginFloor"));
                m.put("report", report);
            } catch (Exception ignored) {
                // an unreadable stored report still lists by name
            }
            out.add(m);
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
