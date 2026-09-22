package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.ActionCheck;
import com.bss.ontology.dto.ExecuteReceipt;
import com.bss.ontology.dto.SweepResult;
import com.bss.ontology.dto.UpgradeOption;
import com.bss.ontology.exception.NotFoundException;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.OutcomeSweeper;
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
 * Check and execute. The body is the action's inputs by name — the shape is the
 * action's own declaration, so it arrives as a node; the caller's own token and
 * X-Channel travel downstream unchanged.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ActionController {

    private final ActionCheckService checks;
    private final ActionExecuteService executes;
    private final UpgradeService upgrades;
    private final TenantScope tenantScope;
    private final OutcomeSweeper sweeper;

    public ActionController(ActionCheckService checks, ActionExecuteService executes, UpgradeService upgrades,
            TenantScope tenantScope, OutcomeSweeper sweeper) {
        this.sweeper = sweeper;
        this.checks = checks;
        this.executes = executes;
        this.upgrades = upgrades;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/actions/{name}/check")
    public ActionCheck check(@PathVariable String name, @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Caller caller = Caller.current(request, tenantScope.currentTenantId());
        JsonNode action = checks.actionOf(name, caller);
        if (action == null) {
            throw NotFoundException.forResource("action", name);
        }
        return new ActionCheck(name, checks.check(action, inputs(body), caller));
    }

    @PostMapping("/actions/{name}/execute")
    public ResponseEntity<ExecuteReceipt> execute(@PathVariable String name, @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
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
    public SweepResult sweepOutcomes() {
        int judged = sweeper.sweepTenant(tenantScope.currentTenantId());
        return new SweepResult(judged, tenantScope.currentTenantId());
    }

    @GetMapping("/subscriptions/{id}/availableUpgrades")
    public List<UpgradeOption> availableUpgrades(@PathVariable String id, HttpServletRequest request) {
        return upgrades.availableUpgrades(id, Caller.current(request, tenantScope.currentTenantId()));
    }

    /** The inputs by name, flat or under {@code inputs}; a null value is an input not given. */
    static Map<String, String> inputs(JsonNode body) {
        Map<String, String> in = new LinkedHashMap<>();
        if (body == null || !body.isObject()) {
            return in;
        }
        JsonNode nested = body.get("inputs");
        JsonNode src = nested != null && nested.isObject() ? nested : body;
        src.fields().forEachRemaining(f -> {
            if (!f.getValue().isNull()) {
                in.put(f.getKey(), f.getValue().asText());
            }
        });
        return in;
    }
}
