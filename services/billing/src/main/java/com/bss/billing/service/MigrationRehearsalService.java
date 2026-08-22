package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.MigrationRehearsal;
import com.bss.billing.repository.MigrationRehearsalRepository;
import com.bss.billing.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S6 — MIGRATION REHEARSAL: the parallel bill run pointed at a legacy
 * export, BEFORE anyone migrates anything. Each legacy row (external ref,
 * plan name, what the old system charges) is mapped against THIS catalog and
 * priced by the same engine that will cut the real bills: matched within
 * tolerance, price-differs by exactly how much, or offering-missing — the
 * exceptions BY NAME, which is what turns the scariest sentence in a BSS
 * sale ("we'll migrate you") into a report. Read-only against production;
 * the persisted report is the only thing written.
 */
@Service
public class MigrationRehearsalService {

    private final BillingRunService runService;
    private final DownstreamClients.CatalogClient catalog;
    private final MigrationRehearsalRepository reports;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public MigrationRehearsalService(BillingRunService runService,
            DownstreamClients.CatalogClient catalog, MigrationRehearsalRepository reports,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.runService = runService;
        this.catalog = catalog;
        this.reports = reports;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> rehearse(Map<String, Object> request) {
        if (!(request.get("rows") instanceof List<?> rawRows) || rawRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "rows [{externalRef, offeringName, expectedMonthly}] are required");
        }
        BigDecimal tolerance = request.get("tolerance") == null ? new BigDecimal("0.01")
                : new BigDecimal(String.valueOf(request.get("tolerance")));

        Map<String, BigDecimal> monthlyByName = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> differs = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        for (Object raw : rawRows) {
            Map<String, Object> row = (Map<String, Object>) raw;
            String externalRef = String.valueOf(row.get("externalRef"));
            String offeringName = String.valueOf(row.get("offeringName"));
            BigDecimal expected = row.get("expectedMonthly") == null ? null
                    : new BigDecimal(String.valueOf(row.get("expectedMonthly")));
            BigDecimal current = monthlyByName.computeIfAbsent(offeringName, name -> {
                List<Map<String, Object>> found = catalog.offeringsByName(name);
                if (found.isEmpty()) {
                    return null;
                }
                String id = String.valueOf(found.get(0).get("id"));
                return runService.monthlyFor(id, new java.util.TreeMap<>(), unitCache);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("externalRef", externalRef);
            out.put("offeringName", offeringName);
            out.put("expectedMonthly", expected);
            if (current == null || current.signum() <= 0) {
                out.put("reason", "no offering with this name carries a recurring price in this catalog");
                missing.add(out);
                continue;
            }
            out.put("currentMonthly", current);
            BigDecimal delta = expected == null ? null : current.subtract(expected);
            out.put("delta", delta);
            if (expected != null && delta.abs().compareTo(tolerance) <= 0) {
                matched.add(out);
            } else {
                differs.add(out);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("@type", "MigrationRehearsal");
        report.put("rows", rawRows.size());
        report.put("matched", matched.size());
        report.put("priceDiffers", differs.size());
        report.put("offeringMissing", missing.size());
        report.put("readyToCutOver", differs.isEmpty() && missing.isEmpty());
        report.put("exceptions", Map.of("priceDiffers", differs, "offeringMissing", missing));
        report.put("assumptions", List.of(
                "priced by the SAME engine that cuts real bills (base recurring, no characteristics)",
                "tolerance " + tolerance + " per month",
                "read-only: nothing was migrated, billed or changed — the report is the only write"));

        MigrationRehearsal row = new MigrationRehearsal();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setName(request.get("name") == null
                ? "Rehearsal: " + rawRows.size() + " rows" : String.valueOf(request.get("name")));
        try {
            row.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            throw new IllegalStateException("report not serialisable", e);
        }
        row.setCreatedAt(OffsetDateTime.now());
        reports.save(row);
        report.put("id", row.getId());
        report.put("name", row.getName());
        return report;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MigrationRehearsal r : reports.findTop50ByTenantIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("createdAt", r.getCreatedAt());
            try {
                Map<String, Object> report = objectMapper.readValue(r.getReportJson(), Map.class);
                m.put("rows", report.get("rows"));
                m.put("matched", report.get("matched"));
                m.put("priceDiffers", report.get("priceDiffers"));
                m.put("offeringMissing", report.get("offeringMissing"));
                m.put("readyToCutOver", report.get("readyToCutOver"));
                m.put("report", report);
            } catch (Exception ignored) {
                // unreadable stored report still lists by name
            }
            out.add(m);
        }
        return out;
    }
}
