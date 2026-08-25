package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.ChaosReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The chaos twin's door: a failure mode priced off the real ledger. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/chaosReport")
public class ChaosReportController {

    private final ChaosReportService chaos;

    public ChaosReportController(ChaosReportService chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam(defaultValue = "psp-outage") String scenario,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(chaos.pspOutage(days));
    }
}
