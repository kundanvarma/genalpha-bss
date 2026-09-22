package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.dto.ImsiRangeRequest;
import com.bss.usage.dto.ImsiRangeView;
import com.bss.usage.dto.SimulateWholesaleRequest;
import com.bss.usage.dto.WholesaleLedgerView;
import com.bss.usage.dto.WholesaleRateCardRequest;
import com.bss.usage.dto.WholesaleRateCardView;
import com.bss.usage.dto.WholesaleSettlement;
import com.bss.usage.dto.WholesaleSimulation;
import com.bss.usage.service.WholesaleUsageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Mobile wholesale (MVNE), seeker side: rate the MVNO's CDRs at the host's
 * wholesale rate card, read the settlement + reconciliation, and manage the rate
 * card + the IMSI range the host lent. All under the usage BASE_PATH — the
 * wholesale paths accept wholesale:admin as well as usage:read/write (see
 * SecurityConfig), so a wholesale operator needs nothing extra.
 */
@RestController
public class WholesaleUsageController {

    private final WholesaleUsageService service;

    public WholesaleUsageController(WholesaleUsageService service) {
        this.service = service;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/rateWholesale")
    public ResponseEntity<List<WholesaleLedgerView>> rateWholesale(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        return ResponseEntity.ok(service.rateWholesale(periodStart, periodEnd));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/simulateWholesale")
    public ResponseEntity<WholesaleSimulation> simulateWholesale(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestBody(required = false) SimulateWholesaleRequest body) {
        return ResponseEntity.ok(service.simulateWholesale(periodStart, periodEnd,
                (body == null ? SimulateWholesaleRequest.EMPTY : body).rates()));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/wholesaleUsageLedger")
    public ResponseEntity<List<WholesaleLedgerView>> ledger(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return ResponseEntity.ok(service.ledgerFor(periodStart));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/mobileWholesaleSettlement")
    public ResponseEntity<WholesaleSettlement> settlement(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        return ResponseEntity.ok(service.settlement(periodStart, periodEnd));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/wholesaleRateCard")
    public ResponseEntity<WholesaleRateCardView> upsertRateCard(@RequestBody WholesaleRateCardRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upsertRateCard(dto));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/wholesaleRateCard")
    public ResponseEntity<List<WholesaleRateCardView>> rateCards() {
        return ResponseEntity.ok(service.rateCards());
    }

    @PostMapping(ApiConstants.BASE_PATH + "/imsiRange")
    public ResponseEntity<ImsiRangeView> allocateImsi(@RequestBody ImsiRangeRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.allocateImsi(dto));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/imsiRange")
    public ResponseEntity<List<ImsiRangeView>> imsiRanges() {
        return ResponseEntity.ok(service.imsiRanges());
    }
}
