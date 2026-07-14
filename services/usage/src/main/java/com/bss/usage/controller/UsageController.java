package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.service.UsageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
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
    private final com.bss.usage.api.FieldSelector fieldSelector;

    public UsageController(UsageService service, com.bss.usage.api.FieldSelector fieldSelector) {
        this.service = service;
        this.fieldSelector = fieldSelector;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/usage")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.ingest(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usage")
    public ResponseEntity<List<Map<String, Object>>> listUsage(@RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(service.findUsage(cleanFilters(allParams)));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usage/{id}")
    public ResponseEntity<Map<String, Object>> getUsage(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findUsageById(id));
    }

    /** Gift remaining GB to a family member — the caller's own data, their call. */
    @PostMapping(ApiConstants.BASE_PATH + "/gift")
    public ResponseEntity<Map<String, Object>> gift(@RequestBody Map<String, Object> dto) {
        Object amount = dto.get("amount");
        return ResponseEntity.ok(service.giftData(String.valueOf(dto.get("receiverId")),
                amount == null ? null : new java.math.BigDecimal(String.valueOf(amount))));
    }

    /** Month close: unused GB rolls into next cycle (back-office/scheduler). */
    @PostMapping(ApiConstants.BASE_PATH + "/cycleClose")
    public ResponseEntity<Map<String, Object>> cycleClose() {
        return ResponseEntity.ok(service.cycleClose());
    }

    // ---- UsageSpecification (TMF635) ----

    @PostMapping(ApiConstants.BASE_PATH + "/usageSpecification")
    public ResponseEntity<Map<String, Object>> createSpec(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createSpec(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageSpecification")
    public ResponseEntity<List<Map<String, Object>>> listSpecs(@RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(service.findSpecs(cleanFilters(allParams)));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageSpecification/{id}")
    public ResponseEntity<Map<String, Object>> getSpec(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findSpecById(id));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/usageSpecification/{id}")
    public ResponseEntity<Map<String, Object>> patchSpec(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchSpec(id, dto));
    }

    @DeleteMapping(ApiConstants.BASE_PATH + "/usageSpecification/{id}")
    public ResponseEntity<Void> deleteSpec(@PathVariable("id") String id) {
        service.deleteSpec(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> cleanFilters(Map<String, String> allParams) {
        Map<String, String> f = new HashMap<>(allParams);
        f.keySet().removeAll(List.of("offset", "limit", "fields", "sort"));
        return f;
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

    // ---- TMF677 usageConsumptionReport resource ----

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/usageConsumptionReport")
    public ResponseEntity<List<?>> listReports(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<Map<String, Object>> items = service.findReports(cleanFilters(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/usageConsumptionReport/{id}")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findReportById(id));
    }
}
