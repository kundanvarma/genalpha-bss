package com.bss.userroles.controller;

import com.bss.userroles.service.TenantOnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OPERATOR-AS-A-FORM: the HOST operator's admin creates a whole new
 * operator from the console. Guarded twice: the roles:admin authority
 * (SecurityConfig) and the host-tenant check here — a hosted operator's
 * admin runs THEIR operator, never mints new ones.
 */
@RestController
public class TenantOnboardingController {

    private final TenantOnboardingService onboarding;
    private final com.bss.userroles.security.TenantScope tenantScope;
    private final String hostTenant;

    public TenantOnboardingController(TenantOnboardingService onboarding,
            com.bss.userroles.security.TenantScope tenantScope,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.tenants.default-tenant:genalpha}") String hostTenant) {
        this.onboarding = onboarding;
        this.tenantScope = tenantScope;
        this.hostTenant = hostTenant;
    }

    @GetMapping("/onboarding/v1/operator")
    public ResponseEntity<List<Map<String, Object>>> list() {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.list());
    }

    @PostMapping("/onboarding/v1/operator")
    public ResponseEntity<Map<String, Object>> onboard(@RequestBody Map<String, Object> dto)
            throws Exception {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.onboard(dto));
    }

    private void requireHostOperator() {
        if (!hostTenant.equals(tenantScope.currentTenantId())) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operators are minted by the HOST operator only");
        }
    }
}
