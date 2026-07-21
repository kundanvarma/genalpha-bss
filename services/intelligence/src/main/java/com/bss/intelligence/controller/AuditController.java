package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.audit.AiAudit;
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
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> rows = audits
                .findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().limit(100).map(this::toMap).toList();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(rows.size())).body(rows);
    }

    private Map<String, Object> toMap(AiAudit a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("useCase", a.getUseCase());
        map.put("provider", a.getProvider());
        map.put("model", a.getModel());
        map.put("prompt", preview(a.getPrompt()));
        map.put("response", preview(a.getResponse()));
        map.put("createdAt", a.getCreatedAt().toString());
        // the control-plane columns: what it cost, how it ended, what it did
        map.put("tokens", (a.getPromptTokens() == null ? 0 : a.getPromptTokens())
                + (a.getCompletionTokens() == null ? 0 : a.getCompletionTokens()));
        map.put("costMicros", a.getCostMicros() == null ? 0 : a.getCostMicros());
        map.put("outcome", a.getOutcome());
        map.put("action", a.getAction());
        return map;
    }

    private static String preview(String s) {
        return s == null ? "" : s.length() <= 240 ? s : s.substring(0, 240) + "…";
    }
}
