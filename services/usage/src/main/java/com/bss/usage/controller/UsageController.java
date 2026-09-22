package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.dto.ConsumptionReport;
import com.bss.usage.dto.CycleCloseReceipt;
import com.bss.usage.dto.DataGift;
import com.bss.usage.dto.GiftRequest;
import com.bss.usage.dto.RateUsageRequest;
import com.bss.usage.dto.RatedChargeView;
import com.bss.usage.dto.UsageAllowanceRequest;
import com.bss.usage.dto.UsageAllowanceView;
import com.bss.usage.dto.UsageSpecificationView;
import com.bss.usage.dto.UsageView;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.service.UsageService;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMF635 side: POST /usage is the mediation/OCS seam; /usageAllowance is
 * admin rule data; /rateUsage is the billing run's task endpoint. All writes
 * are machine/back-office (usage:write). TMF677 side: the consumption report,
 * party-scoped for customers. The TMF635 documents (usage, specification)
 * are stored verbatim, so their bodies are open documents; the house shapes
 * are records.
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
    public ResponseEntity<UsageView> ingest(@RequestBody ObjectNode dto) {
        UsageView created = service.ingest(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usage")
    public ResponseEntity<List<UsageView>> listUsage(@RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(service.findUsage(cleanFilters(allParams)));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usage/{id}")
    public ResponseEntity<UsageView> getUsage(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findUsageById(id));
    }

    /** Gift remaining GB — to a family member by id, or to any number the
     * plan's giftScope reaches. The caller's own data, their call. */
    @PostMapping(ApiConstants.BASE_PATH + "/gift")
    public ResponseEntity<DataGift> gift(@RequestBody GiftRequest dto) {
        return ResponseEntity.ok(service.giftData(dto.receiverId(), dto.receiverPhone(), dto.amount()));
    }

    /** Month close: unused GB rolls into next cycle (back-office/scheduler). */
    @PostMapping(ApiConstants.BASE_PATH + "/cycleClose")
    public ResponseEntity<CycleCloseReceipt> cycleClose() {
        return ResponseEntity.ok(service.cycleClose());
    }

    // ---- UsageSpecification (TMF635) ----

    @PostMapping(ApiConstants.BASE_PATH + "/usageSpecification")
    public ResponseEntity<UsageSpecificationView> createSpec(@RequestBody ObjectNode dto) {
        UsageSpecificationView created = service.createSpec(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageSpecification")
    public ResponseEntity<List<UsageSpecificationView>> listSpecs(@RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(service.findSpecs(cleanFilters(allParams)));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageSpecification/{id}")
    public ResponseEntity<UsageSpecificationView> getSpec(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findSpecById(id));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/usageSpecification/{id}")
    public ResponseEntity<UsageSpecificationView> patchSpec(@PathVariable("id") String id,
            @RequestBody ObjectNode dto) {
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
    public ResponseEntity<UsageAllowanceView> allowance(@RequestBody UsageAllowanceRequest dto) {
        UsageAllowanceView created = service.createAllowance(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/usageAllowance")
    public ResponseEntity<List<UsageAllowanceView>> allowances() {
        return ResponseEntity.ok(service.listAllowances());
    }

    @PostMapping(ApiConstants.BASE_PATH + "/rateUsage")
    public ResponseEntity<List<RatedChargeView>> rate(@RequestBody RateUsageRequest request) {
        if (request.relatedPartyId() == null || request.periodStart() == null || request.periodEnd() == null) {
            throw new BadRequestException("relatedPartyId, periodStart and periodEnd are required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.rateForParty(request.relatedPartyId(), request.periodStart(), request.periodEnd()));
    }

    /** One round trip rates MANY parties (partyId → charges) — the billing run's
     * fresh-period batch; the per-account HTTP fan-out was the slow part, not
     * the rating arithmetic. Same gate as /rateUsage (usage:write). */
    @PostMapping(ApiConstants.BASE_PATH + "/rateUsageBatch")
    public ResponseEntity<Map<String, List<RatedChargeView>>> rateBatch(@RequestBody RateUsageRequest request) {
        if (request.periodStart() == null || request.periodEnd() == null) {
            throw new BadRequestException("periodStart and periodEnd are required");
        }
        Map<String, List<RatedChargeView>> out = new java.util.LinkedHashMap<>();
        for (String party : request.relatedPartyIds() == null ? List.<String>of() : request.relatedPartyIds()) {
            out.put(party, service.rateForParty(party, request.periodStart(), request.periodEnd()));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/queryUsageConsumption")
    public ResponseEntity<ConsumptionReport> consumption(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(service.consumptionReport(relatedPartyId));
    }

    // ---- TMF677 usageConsumptionReport resource ----

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/usageConsumptionReport")
    public ResponseEntity<List<?>> listReports(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<ConsumptionReport> items = service.findReports(cleanFilters(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping(ApiConstants.CONSUMPTION_BASE_PATH + "/usageConsumptionReport/{id}")
    public ResponseEntity<ConsumptionReport> getReport(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findReportById(id));
    }
}
