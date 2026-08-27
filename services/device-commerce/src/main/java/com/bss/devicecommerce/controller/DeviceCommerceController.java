package com.bss.devicecommerce.controller;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.service.DeviceAgreementService;
import com.bss.devicecommerce.service.TradeInService;
import com.bss.devicecommerce.service.WithdrawalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class DeviceCommerceController {

    private final DeviceAgreementService agreements;
    private final TradeInService tradeIns;
    private final WithdrawalService withdrawals;

    public DeviceCommerceController(DeviceAgreementService agreements, TradeInService tradeIns,
            WithdrawalService withdrawals) {
        this.agreements = agreements;
        this.tradeIns = tradeIns;
        this.withdrawals = withdrawals;
    }

    /* ---------- financing agreements ---------- */

    /** The checkout chooser: per-model monthly and total cost, before signing. */
    @PostMapping("/financingQuote")
    public ResponseEntity<Map<String, Object>> financingQuote(@RequestBody Map<String, Object> terms) {
        return ResponseEntity.ok(agreements.quote(terms));
    }

    @PostMapping("/deviceAgreement")
    public ResponseEntity<Map<String, Object>> createAgreement(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = agreements.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/deviceAgreement")
    public ResponseEntity<List<Map<String, Object>>> listAgreements(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(agreements.findAll(relatedPartyId, status));
    }

    @GetMapping("/deviceAgreement/{id}")
    public ResponseEntity<Map<String, Object>> agreementById(@PathVariable String id) {
        return ResponseEntity.ok(agreements.findById(id));
    }

    @GetMapping("/deviceAgreement/{id}/upgradeEligibility")
    public ResponseEntity<Map<String, Object>> upgradeEligibility(@PathVariable String id) {
        return ResponseEntity.ok(agreements.eligibilityOf(id));
    }

    @GetMapping("/deviceAgreement/{id}/earlySettlementQuote")
    public ResponseEntity<Map<String, Object>> earlySettlementQuote(@PathVariable String id) {
        return ResponseEntity.ok(agreements.earlySettlementQuote(id));
    }

    /** Bill run / dunning feed: one instalment landed (staff/machine). */
    @PostMapping("/deviceAgreement/{id}/recordInstallment")
    public ResponseEntity<Map<String, Object>> recordInstallment(@PathVariable String id) {
        return ResponseEntity.ok(agreements.recordInstallment(id));
    }

    /** Early termination without a swap — the ETF path. */
    @PostMapping("/deviceAgreement/{id}/settle")
    public ResponseEntity<Map<String, Object>> settle(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ResponseEntity.ok(agreements.settle(id, dto));
    }

    /** The financier's payout callback (machine face; mock drivers self-serve). */
    @PostMapping("/deviceAgreement/{id}/payoutWebhook")
    public ResponseEntity<Map<String, Object>> payoutWebhook(@PathVariable String id) {
        return ResponseEntity.ok(agreements.payoutWebhook(id));
    }

    /** The upgrade/swap saga: eligibility, trade-in, model-specific settlement. */
    @PostMapping("/deviceAgreement/{id}/swap")
    public ResponseEntity<Map<String, Object>> swap(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ResponseEntity.ok(agreements.swap(id, dto));
    }

    /* ---------- trade-in ---------- */

    @PostMapping("/tradeInValuation")
    public ResponseEntity<Map<String, Object>> quoteTradeIn(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = tradeIns.quote(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/tradeInValuation")
    public ResponseEntity<List<Map<String, Object>>> listTradeIns(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(tradeIns.findAll(relatedPartyId, status));
    }

    @GetMapping("/tradeInValuation/{id}")
    public ResponseEntity<Map<String, Object>> tradeInById(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.findById(id));
    }

    @PostMapping("/tradeInValuation/{id}/accept")
    public ResponseEntity<Map<String, Object>> acceptTradeIn(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.accept(id));
    }

    @PostMapping("/tradeInValuation/{id}/inTransit")
    public ResponseEntity<Map<String, Object>> tradeInInTransit(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.inTransit(id));
    }

    /** The grading partner's verdict (staff/machine). */
    @PostMapping("/tradeInValuation/{id}/grading")
    public ResponseEntity<Map<String, Object>> grade(@PathVariable String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(tradeIns.grade(id, dto));
    }

    @PostMapping("/tradeInValuation/{id}/acceptRevaluation")
    public ResponseEntity<Map<String, Object>> acceptRevaluation(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.acceptRevaluation(id));
    }

    @PostMapping("/tradeInValuation/{id}/rejectRevaluation")
    public ResponseEntity<Map<String, Object>> rejectRevaluation(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.rejectRevaluation(id));
    }

    /* ---------- residual table (staff-curated) ---------- */

    @GetMapping("/tradeInResidual")
    public ResponseEntity<List<Map<String, Object>>> residualTable(
            @RequestParam(name = "deviceRef", required = false) String deviceRef) {
        return ResponseEntity.ok(tradeIns.residualTable(deviceRef));
    }

    @PostMapping("/tradeInResidual")
    public ResponseEntity<Map<String, Object>> upsertResidual(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(tradeIns.upsertResidual(dto));
    }

    @DeleteMapping("/tradeInResidual/{id}")
    public ResponseEntity<Void> deleteResidual(@PathVariable String id) {
        tradeIns.deleteResidual(id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- blacklist flag stub ---------- */

    @PostMapping("/deviceFlag")
    public ResponseEntity<Map<String, Object>> flag(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(tradeIns.flag(dto));
    }

    @GetMapping("/deviceFlag")
    public ResponseEntity<List<Map<String, Object>>> flags(
            @RequestParam(name = "imei", required = false) String imei) {
        return ResponseEntity.ok(tradeIns.flagsOf(imei));
    }

    /* ---------- withdrawal ---------- */

    @PostMapping("/withdrawalCase")
    public ResponseEntity<Map<String, Object>> openWithdrawal(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = withdrawals.open(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/withdrawalCase")
    public ResponseEntity<List<Map<String, Object>>> listWithdrawals(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(withdrawals.findAll(relatedPartyId));
    }

    @GetMapping("/withdrawalCase/{id}")
    public ResponseEntity<Map<String, Object>> withdrawalById(@PathVariable String id) {
        return ResponseEntity.ok(withdrawals.findById(id));
    }
}
