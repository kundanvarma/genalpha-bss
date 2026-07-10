package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.BillingRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Back-office task: cut this period's bills. Customers cannot trigger it. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class BillingRunController {

    private final BillingRunService service;

    public BillingRunController(BillingRunService service) {
        this.service = service;
    }

    @PostMapping("/billingRun")
    public ResponseEntity<Map<String, Object>> run() {
        return ResponseEntity.ok(service.run());
    }
}
