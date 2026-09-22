package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.BillingRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bss.billing.dto.BillingRunResult;
import com.bss.billing.dto.BillingRunView;

/** Back-office task: cut this period's bills. Customers cannot trigger it. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class BillingRunController {

    private final BillingRunService service;

    public BillingRunController(BillingRunService service) {
        this.service = service;
    }

    @PostMapping("/billingRun")
    public ResponseEntity<BillingRunResult> run() {
        return ResponseEntity.ok(service.run());
    }

    /** The run ledger: every recent run's face, newest first. */
    @org.springframework.web.bind.annotation.GetMapping("/billingRun")
    public ResponseEntity<java.util.List<BillingRunView>> recent() {
        return ResponseEntity.ok(service.recentRuns());
    }
}
