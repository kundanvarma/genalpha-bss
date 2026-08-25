package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.PortfolioDiffService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The clone's answer sheet: two portfolios, one pricing engine, the
 *  difference by name. Billing-admin (host desk) ground. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/portfolioDiff")
public class PortfolioDiffController {

    private final PortfolioDiffService diff;

    public PortfolioDiffController(PortfolioDiffService diff) {
        this.diff = diff;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> diff(@RequestParam String tenantA,
            @RequestParam String tenantB) {
        return ResponseEntity.ok(diff.diff(tenantA, tenantB));
    }
}
