package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.exception.NotFoundException;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.UpgradeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Check and execute. The body is the action's inputs by name; the caller's own
 * token and X-Channel travel downstream unchanged.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ActionController {

    private final ActionCheckService checks;
    private final ActionExecuteService executes;
    private final UpgradeService upgrades;
    private final TenantScope tenantScope;
    private final com.bss.ontology.service.OutcomeSweeper sweeper;

    public ActionController(ActionCheckService checks, ActionExecuteService executes, UpgradeService upgrades,
            TenantScope tenantScope, com.bss.ontology.service.OutcomeSweeper sweeper) {
        this.sweeper = sweeper;
        this.checks = checks;
        this.executes = executes;
        this.upgrades = upgrades;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/actions/{name}/check")
    public Map<String, Object> check(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Caller caller = Caller.current(request, tenantScope.currentTenantId());
        JsonNode action = checks.actionOf(name, caller);
        if (action == null) {
            throw NotFoundException.forResource("action", name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", name);
        out.putAll(checks.check(action, inputs(body), caller).toMap());
        return out;
    }

    @PostMapping("/actions/{name}/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String name,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Caller caller = Caller.current(request, tenantScope.currentTenantId());
        JsonNode action = checks.actionOf(name, caller);
        if (action == null) {
            throw NotFoundException.forResource("action", name);
        }
        ActionExecuteService.Outcome o = executes.execute(action, inputs(body), caller);
        return ResponseEntity.status(o.status()).body(o.body());
    }

    /** Judge the receipts whose outcome window has passed, now (the scheduler does the same on its own clock). */
    @PostMapping("/outcomes/sweep")
    public Map<String, Object> sweepOutcomes() {
        int judged = sweeper.sweepTenant(tenantScope.currentTenantId());
        return Map.of("judged", judged, "tenant", tenantScope.currentTenantId());
    }

    @GetMapping("/subscriptions/{id}/availableUpgrades")
    public List<Map<String, Object>> availableUpgrades(@PathVariable String id, HttpServletRequest request) {
        return upgrades.availableUpgrades(id, Caller.current(request, tenantScope.currentTenantId()));
    }

    static Map<String, String> inputs(Map<String, Object> body) {
        Map<String, String> in = new LinkedHashMap<>();
        if (body == null) {
            return in;
        }
        Object nested = body.get("inputs");
        Map<?, ?> src = nested instanceof Map<?, ?> m ? m : body;
        src.forEach((k, v) -> {
            if (v != null) {
                in.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return in;
    }
}
