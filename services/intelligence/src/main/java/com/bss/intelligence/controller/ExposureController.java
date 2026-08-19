package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI data-flows readout (Tvilling T-P3): what CLASS of data left, per
 * use-case, to which provider and jurisdiction, how often — computed live
 * from the same ledger the governor writes. Compliance as a readout, not
 * an annual PDF.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ExposureController {

    private final AiAuditRepository audits;
    private final TenantScope tenantScope;

    public ExposureController(AiAuditRepository audits, TenantScope tenantScope) {
        this.audits = audits;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/exposure")
    public ResponseEntity<Map<String, Object>> exposure() {
        List<Object[]> rows = audits.exposureSummary(tenantScope.currentTenantId());
        Map<String, Integer> byClass = new LinkedHashMap<>();
        List<Map<String, Object>> flows = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            String useCase = String.valueOf(r[0]);
            String exposure = r[1] == null ? "unlabelled" : String.valueOf(r[1]);
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("useCase", useCase);
            flow.put("exposure", exposure);
            flow.put("provider", r[2] == null ? "-" : r[2]);
            flow.put("jurisdiction", r[3] == null ? "-" : r[3]);
            flow.put("calls", ((Number) r[4]).longValue());
            flow.put("lastCall", r[5]);
            flows.add(flow);
            byClass.merge(exposure, ((Number) r[4]).intValue(), Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byExposure", byClass);
        out.put("flows", flows);
        out.put("classes", Map.of(
                "none", "nothing left (local/stub)",
                "twin", "a deterministic fiction left — no real fact (Tvilling)",
                "aggregate", "counts and rollups left — never customer text",
                "raw", "raw customer data left — tenant-gated opt-in",
                "unlabelled", "recorded before exposure receipts existed"));
        out.put("@type", "AiExposureReport");
        return ResponseEntity.ok(out);
    }
}
