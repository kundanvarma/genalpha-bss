package com.bss.devicecommerce.controller;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.DeviceAgreementView;
import com.bss.devicecommerce.dto.DeviceFlagRequest;
import com.bss.devicecommerce.dto.DeviceFlagView;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.dto.GradingRequest;
import com.bss.devicecommerce.dto.ResidualRequest;
import com.bss.devicecommerce.dto.SettleReceipt;
import com.bss.devicecommerce.dto.SettleRequest;
import com.bss.devicecommerce.dto.SwapReceipt;
import com.bss.devicecommerce.dto.SwapRequest;
import com.bss.devicecommerce.dto.TradeInQuoteRequest;
import com.bss.devicecommerce.dto.TradeInResidualView;
import com.bss.devicecommerce.dto.TradeInValuationView;
import com.bss.devicecommerce.dto.UpgradeEligibility;
import com.bss.devicecommerce.dto.WithdrawalCaseView;
import com.bss.devicecommerce.dto.WithdrawalReceipt;
import com.bss.devicecommerce.dto.WithdrawalRequest;
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
    public ResponseEntity<FinancingQuote> financingQuote(@RequestBody FinancingTerms terms) {
        return ResponseEntity.ok(agreements.quote(terms));
    }

    @PostMapping("/deviceAgreement")
    public ResponseEntity<DeviceAgreementView> createAgreement(@RequestBody DeviceAgreementRequest dto) {
        DeviceAgreementView created = agreements.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/deviceAgreement")
    public ResponseEntity<List<DeviceAgreementView>> listAgreements(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(agreements.findAll(relatedPartyId, status));
    }

    @GetMapping("/deviceAgreement/{id}")
    public ResponseEntity<DeviceAgreementView> agreementById(@PathVariable String id) {
        return ResponseEntity.ok(agreements.findById(id));
    }

    @GetMapping("/deviceAgreement/{id}/upgradeEligibility")
    public ResponseEntity<UpgradeEligibility> upgradeEligibility(@PathVariable String id) {
        return ResponseEntity.ok(agreements.eligibilityOf(id));
    }

    @GetMapping("/deviceAgreement/{id}/earlySettlementQuote")
    public ResponseEntity<EarlySettlementQuote> earlySettlementQuote(@PathVariable String id) {
        return ResponseEntity.ok(agreements.earlySettlementQuote(id));
    }

    /** Bill run / dunning feed: one instalment landed (staff/machine). */
    @PostMapping("/deviceAgreement/{id}/recordInstallment")
    public ResponseEntity<DeviceAgreementView> recordInstallment(@PathVariable String id) {
        return ResponseEntity.ok(agreements.recordInstallment(id));
    }

    /** Early termination without a swap — the ETF path. */
    @PostMapping("/deviceAgreement/{id}/settle")
    public ResponseEntity<SettleReceipt> settle(@PathVariable String id,
            @RequestBody(required = false) SettleRequest dto) {
        return ResponseEntity.ok(agreements.settle(id, dto == null ? SettleRequest.EMPTY : dto));
    }

    /** The financier's payout callback (machine face; mock drivers self-serve). */
    @PostMapping("/deviceAgreement/{id}/payoutWebhook")
    public ResponseEntity<DeviceAgreementView> payoutWebhook(@PathVariable String id) {
        return ResponseEntity.ok(agreements.payoutWebhook(id));
    }

    /** The upgrade/swap saga: eligibility, trade-in, model-specific settlement. */
    @PostMapping("/deviceAgreement/{id}/swap")
    public ResponseEntity<SwapReceipt> swap(@PathVariable String id,
            @RequestBody(required = false) SwapRequest dto) {
        return ResponseEntity.ok(agreements.swap(id, dto == null ? SwapRequest.EMPTY : dto));
    }

    /* ---------- trade-in ---------- */

    @PostMapping("/tradeInValuation")
    public ResponseEntity<TradeInValuationView> quoteTradeIn(@RequestBody TradeInQuoteRequest dto) {
        TradeInValuationView created = tradeIns.quote(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/tradeInValuation")
    public ResponseEntity<List<TradeInValuationView>> listTradeIns(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(tradeIns.findAll(relatedPartyId, status));
    }

    @GetMapping("/tradeInValuation/{id}")
    public ResponseEntity<TradeInValuationView> tradeInById(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.findById(id));
    }

    @PostMapping("/tradeInValuation/{id}/accept")
    public ResponseEntity<TradeInValuationView> acceptTradeIn(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.accept(id));
    }

    @PostMapping("/tradeInValuation/{id}/inTransit")
    public ResponseEntity<TradeInValuationView> tradeInInTransit(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.inTransit(id));
    }

    /** The grading partner's verdict (staff/machine). */
    @PostMapping("/tradeInValuation/{id}/grading")
    public ResponseEntity<TradeInValuationView> grade(@PathVariable String id,
            @RequestBody GradingRequest dto) {
        return ResponseEntity.ok(tradeIns.grade(id, dto));
    }

    @PostMapping("/tradeInValuation/{id}/acceptRevaluation")
    public ResponseEntity<TradeInValuationView> acceptRevaluation(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.acceptRevaluation(id));
    }

    @PostMapping("/tradeInValuation/{id}/rejectRevaluation")
    public ResponseEntity<TradeInValuationView> rejectRevaluation(@PathVariable String id) {
        return ResponseEntity.ok(tradeIns.rejectRevaluation(id));
    }

    /* ---------- residual table (staff-curated) ---------- */

    @GetMapping("/tradeInResidual")
    public ResponseEntity<List<TradeInResidualView>> residualTable(
            @RequestParam(name = "deviceRef", required = false) String deviceRef) {
        return ResponseEntity.ok(tradeIns.residualTable(deviceRef));
    }

    @PostMapping("/tradeInResidual")
    public ResponseEntity<TradeInResidualView> upsertResidual(@RequestBody ResidualRequest dto) {
        return ResponseEntity.ok(tradeIns.upsertResidual(dto));
    }

    @DeleteMapping("/tradeInResidual/{id}")
    public ResponseEntity<Void> deleteResidual(@PathVariable String id) {
        tradeIns.deleteResidual(id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- blacklist flag stub ---------- */

    @PostMapping("/deviceFlag")
    public ResponseEntity<DeviceFlagView> flag(@RequestBody DeviceFlagRequest dto) {
        return ResponseEntity.ok(tradeIns.flag(dto));
    }

    @GetMapping("/deviceFlag")
    public ResponseEntity<List<DeviceFlagView>> flags(
            @RequestParam(name = "imei", required = false) String imei) {
        return ResponseEntity.ok(tradeIns.flagsOf(imei));
    }

    /* ---------- withdrawal ---------- */

    @PostMapping("/withdrawalCase")
    public ResponseEntity<WithdrawalReceipt> openWithdrawal(@RequestBody WithdrawalRequest dto) {
        WithdrawalReceipt created = withdrawals.open(dto);
        return ResponseEntity.created(URI.create(created.withdrawal().href())).body(created);
    }

    @GetMapping("/withdrawalCase")
    public ResponseEntity<List<WithdrawalCaseView>> listWithdrawals(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(withdrawals.findAll(relatedPartyId));
    }

    @GetMapping("/withdrawalCase/{id}")
    public ResponseEntity<WithdrawalCaseView> withdrawalById(@PathVariable String id) {
        return ResponseEntity.ok(withdrawals.findById(id));
    }
}
