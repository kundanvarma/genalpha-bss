package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.dto.MvnoStatement;
import com.bss.usage.dto.ProviderRateCardRequest;
import com.bss.usage.dto.ProviderRateCardView;
import com.bss.usage.dto.ProviderSettlement;
import com.bss.usage.dto.ProviderUsageRequest;
import com.bss.usage.dto.ProviderUsageResult;
import com.bss.usage.service.ProviderWholesaleService;
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
 * Mobile wholesale PROVIDER face (W-M7): the host MNO / MVNE bills external MVNOs.
 * Under the usage BASE_PATH; the provider paths accept wholesale:admin (see
 * SecurityConfig). The per-MVNO statement is the face an external MVNO's BSS pulls.
 */
@RestController
public class ProviderWholesaleController {

    private final ProviderWholesaleService service;

    public ProviderWholesaleController(ProviderWholesaleService service) {
        this.service = service;
    }

    // The network's mediation feed: an external MVNO's usage for a period.
    @PostMapping(ApiConstants.BASE_PATH + "/mobileWholesaleProviderUsage")
    public ResponseEntity<ProviderUsageResult> record(@RequestBody ProviderUsageRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordProviderUsage(dto));
    }

    // The host's consolidated book: per MVNO, what each owes (host AR).
    @GetMapping(ApiConstants.BASE_PATH + "/mobileWholesaleProviderSettlement")
    public ResponseEntity<ProviderSettlement> settlement(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return ResponseEntity.ok(service.providerSettlement(periodStart));
    }

    // One MVNO's statement — the machine face an external MVNO's own BSS pulls.
    @GetMapping(ApiConstants.BASE_PATH + "/mobileWholesaleStatement")
    public ResponseEntity<MvnoStatement> statement(
            @RequestParam("mvnoPartyId") String mvnoPartyId,
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return ResponseEntity.ok(service.mvnoStatement(mvnoPartyId, periodStart));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/providerRateCard")
    public ResponseEntity<ProviderRateCardView> upsertRateCard(@RequestBody ProviderRateCardRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upsertProviderRateCard(dto));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/providerRateCard")
    public ResponseEntity<List<ProviderRateCardView>> rateCards() {
        return ResponseEntity.ok(service.providerRateCards());
    }
}
