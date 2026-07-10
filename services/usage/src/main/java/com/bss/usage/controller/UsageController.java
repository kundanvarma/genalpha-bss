package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.service.UsageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * TMF635 side: POST /usage is the mediation/OCS seam; /usageAllowance is
 * admin rule data; /rateUsage is the billing run's task endpoint. All writes
 * are machine/back-office (usage:write). TMF677 side: the consumption report,
 * party-scoped for customers.
 */
@RestController
public class UsageController {

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/usage")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.ingest(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @PostMapping(ApiConstants.BASE_PATH + "/usageAllowance")
    public ResponseEntity<Map<String, Object>> allowance(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createAllowance(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageAllowance")
    public ResponseEntity<List<Map<String, Object>>> allowances() {
        return ResponseEntity.ok(service.listAllowances());
    }

    @PostMapping(ApiConstants.BASE_PATH + "/rateUsage")
    public ResponseEntity<List<Map<String, Object>>> rate(@RequestBody Map<String, Object> request) {
        String party = String.valueOf(request.get("relatedPartyId"));
        LocalDate start = LocalDate.parse(String.valueOf(request.get("periodStart")));
        LocalDate end = LocalDate.parse(String.valueOf(request.get("periodEnd")));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.rateForParty(party, start, end));
    }

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/queryUsageConsumption")
    public ResponseEntity<Map<String, Object>> consumption(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(service.consumptionReport(relatedPartyId));
    }
}
