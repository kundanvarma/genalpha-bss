package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.security.TenantScope;
import com.bss.billing.service.ShadowBillingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The shadow run's worklist: what will bill differently next cycle, and a
 *  sweep-now door so an operator (or a suite) need not wait for the tick. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/shadowDrift")
public class ShadowBillingController {

    private final ShadowBillingService shadow;
    private final TenantScope tenantScope;

    public ShadowBillingController(ShadowBillingService shadow, TenantScope tenantScope) {
        this.shadow = shadow;
        this.tenantScope = tenantScope;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(shadow.list(tenantScope.currentTenantId()));
    }

    @PostMapping("/sweep")
    public ResponseEntity<List<Map<String, Object>>> sweep(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String partyId) {
        return ResponseEntity.ok(shadow.sweep(tenantScope.currentTenantId(), partyId));
    }
}
