package com.bss.userroles.controller;

import com.bss.userroles.dto.AdvanceClockRequest;
import com.bss.userroles.dto.BaseImportReport;
import com.bss.userroles.dto.BrandPatch;
import com.bss.userroles.dto.BrandView;
import com.bss.userroles.dto.CloneReceipt;
import com.bss.userroles.dto.CloneRequest;
import com.bss.userroles.dto.ClockReceipt;
import com.bss.userroles.dto.ImportBaseRequest;
import com.bss.userroles.dto.MutateReceipt;
import com.bss.userroles.dto.OnboardReceipt;
import com.bss.userroles.dto.OnboardRequest;
import com.bss.userroles.dto.OperatorPatch;
import com.bss.userroles.dto.OperatorView;
import com.bss.userroles.dto.ProspectSimulation;
import com.bss.userroles.dto.ProspectSimulationRequest;
import com.bss.userroles.dto.QuarterResult.SimulatedQuarter;
import com.bss.userroles.dto.SeedTwinRequest;
import com.bss.userroles.dto.TwinBaseReceipt;
import com.bss.userroles.exception.BadRequestException;
import com.bss.userroles.security.TenantScope;
import com.bss.userroles.service.TenantOnboardingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OPERATOR-AS-A-FORM: the HOST operator's admin creates a whole new
 * operator from the console. Guarded twice: the roles:admin authority
 * (SecurityConfig) and the host-tenant check here — a hosted operator's
 * admin runs THEIR operator, never mints new ones.
 */
@RestController
public class TenantOnboardingController {

    private final TenantOnboardingService onboarding;
    private final TenantScope tenantScope;
    private final String hostTenant;

    public TenantOnboardingController(TenantOnboardingService onboarding, TenantScope tenantScope,
            @Value("${bss.tenants.default-tenant:genalpha}") String hostTenant) {
        this.onboarding = onboarding;
        this.tenantScope = tenantScope;
        this.hostTenant = hostTenant;
    }

    @GetMapping("/onboarding/v1/operator")
    public ResponseEntity<List<OperatorView>> list() {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.list());
    }

    @PostMapping("/onboarding/v1/operator")
    public ResponseEntity<OnboardReceipt> onboard(@RequestBody OnboardRequest dto) throws Exception {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.onboard(dto));
    }

    /** Live rebrand/re-currency of a serving operator — no restart. */
    @PostMapping("/onboarding/v1/operator/{id}/clone")
    public ResponseEntity<CloneReceipt> cloneOperator(@PathVariable String id, @RequestBody CloneRequest dto)
            throws Exception {
        return ResponseEntity.status(201).body(onboarding.cloneOperator(id, dto));
    }

    @PostMapping("/onboarding/v1/operator/{id}/seedTwinBase")
    public ResponseEntity<TwinBaseReceipt> seedTwinBase(@PathVariable String id, @RequestBody SeedTwinRequest dto)
            throws Exception {
        return ResponseEntity.status(201).body(onboarding.seedTwinBase(id, dto));
    }

    @PostMapping("/onboarding/v1/operator/{id}/advanceClock")
    public ResponseEntity<ClockReceipt> advanceClock(@PathVariable String id, @RequestBody AdvanceClockRequest dto)
            throws Exception {
        return ResponseEntity.ok(onboarding.advanceClock(id, dto.daysOrDefault()));
    }

    @PostMapping("/onboarding/v1/operator/{id}/simulateQuarter")
    public ResponseEntity<SimulatedQuarter> simulateQuarter(@PathVariable String id) throws Exception {
        return ResponseEntity.status(201).body(onboarding.simulateQuarter(id));
    }

    @PostMapping("/onboarding/v1/prospectSimulation")
    public ResponseEntity<ProspectSimulation> prospectSimulation(@RequestBody ProspectSimulationRequest dto)
            throws Exception {
        return ResponseEntity.status(201).body(onboarding.prospectSimulation(dto));
    }

    @PostMapping("/onboarding/v1/operator/{id}/importBase")
    public ResponseEntity<BaseImportReport> importBase(@PathVariable String id, @RequestBody ImportBaseRequest dto)
            throws Exception {
        return ResponseEntity.status(201).body(onboarding.importBase(id, dto));
    }

    @PatchMapping("/onboarding/v1/operator/{id}")
    public ResponseEntity<MutateReceipt> mutate(@PathVariable("id") String id, @RequestBody OperatorPatch dto)
            throws Exception {
        requireHostOperator();
        return ResponseEntity.ok(onboarding.mutate(id, dto));
    }

    /** THE TENANT'S OWN VOICE: a hosted operator's marketing team reads and
     *  edits its storefront brand — no host-admin rights involved. */
    @GetMapping("/onboarding/v1/myOperator")
    public ResponseEntity<List<BrandView>> myOperator() throws Exception {
        return ResponseEntity.ok(List.of(onboarding.brandOf(tenantScope.currentTenantId())));
    }

    @PatchMapping("/onboarding/v1/myOperator/{id}")
    public ResponseEntity<MutateReceipt> mutateOwn(@PathVariable("id") String id, @RequestBody BrandPatch dto)
            throws Exception {
        if (!tenantScope.currentTenantId().equals(id)) {
            throw new BadRequestException("you edit YOUR operator's brand only");
        }
        return ResponseEntity.ok(onboarding.mutateBrand(id, dto));
    }

    private void requireHostOperator() {
        if (!hostTenant.equals(tenantScope.currentTenantId())) {
            throw new BadRequestException("operators are minted by the HOST operator only");
        }
    }
}
