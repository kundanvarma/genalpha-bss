package com.bss.som.controller;

import com.bss.som.dto.DryRunPlan;
import com.bss.som.dto.DryRunRequest;
import com.bss.som.exception.BadRequestException;
import com.bss.som.security.TenantScope;
import com.bss.som.service.FulfilmentDryRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "What will happen when someone orders this?" — the executor's plan for an
 * offering, no adapter called, nothing recorded. A house face beside the TMF
 * ones: the offering page and launch governance read it before a launch.
 */
@RestController
@RequestMapping("/som/v1/fulfilment")
public class FulfilmentDryRunController {

    private final FulfilmentDryRun dryRun;
    private final TenantScope scope;

    public FulfilmentDryRunController(FulfilmentDryRun dryRun, TenantScope scope) {
        this.dryRun = dryRun;
        this.scope = scope;
    }

    @PostMapping("/dryRun")
    public ResponseEntity<DryRunPlan> dryRun(@RequestBody DryRunRequest request) {
        if (request == null || request.offeringId() == null || request.offeringId().isBlank()) {
            throw new BadRequestException("offeringId is required — the offering to plan fulfilment for");
        }
        return ResponseEntity.ok(dryRun.plan(scope.currentTenantId(), request));
    }
}
