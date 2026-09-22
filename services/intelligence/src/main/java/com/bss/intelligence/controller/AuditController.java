package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The transparency ledger, readable: what left the box, what came back,
 * which model answered — the tenant's own calls only. This is the page a
 * DPO asks for in the first meeting.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class AuditController {

    private final AiAuditRepository audits;
    private final TenantScope tenantScope;

    public AuditController(AiAuditRepository audits, TenantScope tenantScope) {
        this.audits = audits;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/audit")
    public ResponseEntity<List<AiAuditView>> list() {
        List<AiAuditView> rows = audits
                .findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().limit(100).map(AuditController::view).toList();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(rows.size())).body(rows);
    }

    /** One ledger row as the audit page reads it: previews, cost, outcome,
     * and whether the provider saw the prompt raw. */
    @JsonPropertyOrder({"id", "useCase", "provider", "model", "prompt", "response", "createdAt",
            "tokens", "costMicros", "outcome", "action", "redactedFields", "rawExposure"})
    public record AiAuditView(String id, String useCase, String provider, String model,
            String prompt, String response, String createdAt, long tokens, long costMicros,
            String outcome, String action, int redactedFields, boolean rawExposure) {
    }

    private static AiAuditView view(AiAudit a) {
        // the control-plane columns: what it cost, how it ended, what it did
        return new AiAuditView(a.getId(), a.getUseCase(), a.getProvider(), a.getModel(),
                preview(a.getPrompt()), preview(a.getResponse()), a.getCreatedAt().toString(),
                (a.getPromptTokens() == null ? 0 : a.getPromptTokens())
                        + (a.getCompletionTokens() == null ? 0 : a.getCompletionTokens()),
                a.getCostMicros() == null ? 0 : a.getCostMicros(),
                a.getOutcome(), a.getAction(),
                a.getRedactedFields() == null ? 0 : a.getRedactedFields(),
                Boolean.TRUE.equals(a.getRawExposure()));
    }

    private static String preview(String s) {
        return s == null ? "" : s.length() <= 240 ? s : s.substring(0, 240) + "…";
    }
}
