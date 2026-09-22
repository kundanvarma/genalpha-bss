package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.MigrationRehearsalDtos.Exceptions;
import com.bss.billing.dto.MigrationRehearsalDtos.LegacyRow;
import com.bss.billing.dto.MigrationRehearsalDtos.Missing;
import com.bss.billing.dto.MigrationRehearsalDtos.Priced;
import com.bss.billing.dto.MigrationRehearsalDtos.Report;
import com.bss.billing.dto.MigrationRehearsalDtos.Request;
import com.bss.billing.dto.MigrationRehearsalDtos.Summary;
import com.bss.billing.entity.MigrationRehearsal;
import com.bss.billing.repository.MigrationRehearsalRepository;
import com.bss.billing.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    public Report rehearse(Request request) {
        List<LegacyRow> rawRows = request == null ? null : request.rows();
        if (rawRows == null || rawRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "rows [{externalRef, offeringName, expectedMonthly}] are required");
        }
        BigDecimal tolerance = request.tolerance() == null ? new BigDecimal("0.01") : request.tolerance();

        Map<String, BigDecimal> monthlyByName = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        List<Priced> matched = new ArrayList<>();
        List<Priced> differs = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();

        for (LegacyRow row : rawRows) {
            String externalRef = String.valueOf(row.externalRef());
            String offeringName = String.valueOf(row.offeringName());
            BigDecimal expected = row.expectedMonthly();
            BigDecimal current = monthlyByName.computeIfAbsent(offeringName, name -> {
                List<Map<String, Object>> found = catalog.offeringsByName(name);
                if (found.isEmpty()) {
                    return null;
                }
                String id = String.valueOf(found.get(0).get("id"));
                return runService.monthlyFor(id, new java.util.TreeMap<>(), unitCache);
            });
            if (current == null || current.signum() <= 0) {
                missing.add(new Missing(externalRef, offeringName, expected,
                        "no offering with this name carries a recurring price in this catalog"));
                continue;
            }
            BigDecimal delta = expected == null ? null : current.subtract(expected);
            Priced out = new Priced(externalRef, offeringName, expected, current, delta);
            if (expected != null && delta.abs().compareTo(tolerance) <= 0) {
                matched.add(out);
            } else {
                differs.add(out);
            }
        }

        Report report = new Report("MigrationRehearsal", rawRows.size(), matched.size(), differs.size(),
                missing.size(), differs.isEmpty() && missing.isEmpty(), new Exceptions(differs, missing),
                List.of(
                "priced by the SAME engine that cuts real bills (base recurring, no characteristics)",
                "tolerance " + tolerance + " per month",
                "read-only: nothing was migrated, billed or changed — the report is the only write"),
                null, null);

        MigrationRehearsal row = new MigrationRehearsal();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setName(request.name() == null ? "Rehearsal: " + rawRows.size() + " rows" : request.name());
        try {
            row.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            throw new IllegalStateException("report not serialisable", e);
        }
        row.setCreatedAt(OffsetDateTime.now());
        reports.save(row);
        return report.saved(row.getId(), row.getName());
    }

    /** The saved reports, newest first; the stored report rides along as the tree it was written as. */
    @Transactional(readOnly = true)
    public List<Summary> list() {
        List<Summary> out = new ArrayList<>();
        for (MigrationRehearsal r : reports.findTop50ByTenantIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId())) {
            JsonNode report = null;
            try {
                report = objectMapper.readTree(r.getReportJson());
            } catch (Exception ignored) {
                // unreadable stored report still lists by name
            }
            out.add(report == null
                    ? new Summary(r.getId(), r.getName(), r.getCreatedAt(), null, null, null, null, null, null)
                    : new Summary(r.getId(), r.getName(), r.getCreatedAt(), report.get("rows"),
                            report.get("matched"), report.get("priceDiffers"), report.get("offeringMissing"),
                            report.get("readyToCutOver"), report));
        }
        return out;
    }
}
