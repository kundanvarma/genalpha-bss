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

    /** Live rebrand/re-currency of a serving operator — no restart. */
    @PostMapping("/onboarding/v1/operator/{id}/clone")
    public ResponseEntity<Map<String, Object>> cloneOperator(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> dto) throws Exception {
        return ResponseEntity.status(201).body(onboarding.cloneOperator(id, dto));
    }

    @PostMapping("/onboarding/v1/operator/{id}/seedTwinBase")
    public ResponseEntity<Map<String, Object>> seedTwinBase(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> dto) throws Exception {
        return ResponseEntity.status(201).body(onboarding.seedTwinBase(id, dto));
    }

    @PostMapping("/onboarding/v1/operator/{id}/advanceClock")
    public ResponseEntity<Map<String, Object>> advanceClock(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> dto) throws Exception {
        return ResponseEntity.ok(onboarding.advanceClock(id,
                Integer.parseInt(String.valueOf(dto.getOrDefault("days", 30)))));
    }

    @PostMapping("/onboarding/v1/operator/{id}/simulateQuarter")
    public ResponseEntity<Map<String, Object>> simulateQuarter(
            @org.springframework.web.bind.annotation.PathVariable String id) throws Exception {
        return ResponseEntity.status(201).body(onboarding.simulateQuarter(id));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/onboarding/v1/operator/{id}")
    public ResponseEntity<Map<String, Object>> mutate(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) throws Exception {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.mutate(id, dto));
    }

    /** THE TENANT'S OWN VOICE: a hosted operator's marketing team reads and
     *  edits its storefront brand — no host-admin rights involved. */
    @GetMapping("/onboarding/v1/myOperator")
    public ResponseEntity<List<Map<String, Object>>> myOperator() throws Exception {
        return ResponseEntity.ok(List.of(onboarding.brandOf(tenantScope.currentTenantId())));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/onboarding/v1/myOperator/{id}")
    public ResponseEntity<Map<String, Object>> mutateOwn(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) throws Exception {
        if (!tenantScope.currentTenantId().equals(id)) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "you edit YOUR operator's brand only");
        }
        return ResponseEntity.ok(onboarding.mutateBrand(id, dto));
    }

    private void requireHostOperator() {
        if (!hostTenant.equals(tenantScope.currentTenantId())) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operators are minted by the HOST operator only");
        }
    }
}
