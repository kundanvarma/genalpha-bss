package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudget;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE CONTROL PLANE, as an operator surface: what a tenant is spending on
 * AI this window, against what ceiling, with how much headroom left — and
 * the agent-action trail (which AI wrote to which resource). The operator
 * SETS the budget and the kill-switch here too. Tenant-isolated by RLS:
 * a tenant sees and sets only its own.
 *
 * (Budget-setting is gated `ai:use` for the demo; a production deployment
 * would separate an `ai:admin` authority — the seam is the matcher.)
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/governance")
public class GovernanceController {

    private final AiBudgetRepository budgets;
    private final AiAuditRepository audits;
    private final TenantScope tenantScope;

    public GovernanceController(AiBudgetRepository budgets, AiAuditRepository audits,
            TenantScope tenantScope) {
        this.budgets = budgets;
        this.audits = audits;
        this.tenantScope = tenantScope;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> governance() {
        String tenant = tenantScope.currentTenantId();
        AiBudget budget = budgets.findByTenantId(tenant).orElse(null);
        int windowHours = budget == null ? 720 : budget.getWindowHours();
        long ceiling = budget == null ? 0 : budget.getBudgetMicros();
        boolean enabled = budget == null || budget.isEnabled();
        long spent = audits.sumCostSince(tenant, OffsetDateTime.now().minusHours(windowHours));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant);
        out.put("enabled", enabled);
        out.put("budgetMicros", ceiling);
        out.put("windowHours", windowHours);
        out.put("spentMicros", spent);
        out.put("remainingMicros", ceiling <= 0 ? -1 : Math.max(0, ceiling - spent));
        out.put("unlimited", ceiling <= 0);
        out.put("maxWorkers", budget == null ? 0 : budget.getMaxWorkers());
        out.put("actions", audits
                .findTop50ByTenantIdAndActionIsNotNullOrderByCreatedAtDesc(tenant)
                .stream().map(this::actionRow).toList());
        return ResponseEntity.ok(out);
    }

    /** Set (or update) this tenant's spend ceiling and kill-switch. */
    @PostMapping("/budget")
    public ResponseEntity<Map<String, Object>> setBudget(@RequestBody Map<String, Object> body) {
        String tenant = tenantScope.currentTenantId();
        AiBudget budget = budgets.findByTenantId(tenant).orElseGet(AiBudget::new);
        budget.setTenantId(tenant);
        if (body.get("budgetMicros") != null) {
            budget.setBudgetMicros(Long.parseLong(String.valueOf(body.get("budgetMicros"))));
        }
        if (body.get("windowHours") != null) {
            budget.setWindowHours(Integer.parseInt(String.valueOf(body.get("windowHours"))));
        }
        if (body.get("enabled") != null) {
            budget.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        }
        if (body.get("maxWorkers") != null) {
            // the crew ceiling: surge staffing never grows past it (0 = unlimited)
            budget.setMaxWorkers(Integer.parseInt(String.valueOf(body.get("maxWorkers"))));
        }
        budget.setLastUpdate(OffsetDateTime.now());
        budgets.save(budget);
        return governance();
    }

    private Map<String, Object> actionRow(AiAudit a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("createdAt", a.getCreatedAt());
        m.put("useCase", a.getUseCase());
        m.put("action", a.getAction());
        m.put("resourceRef", a.getResourceRef());
        m.put("outcome", a.getOutcome());
        return m;
    }
}
