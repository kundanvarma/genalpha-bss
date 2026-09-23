package com.bss.som.controller;

import com.bss.som.dto.DealerDtos.AgreementRequest;
import com.bss.som.dto.DealerDtos.CommissionPage;
import com.bss.som.dto.DealerDtos.DealerAgreementView;
import com.bss.som.dto.DealerDtos.KitActivationReceipt;
import com.bss.som.dto.DealerDtos.KitActivationRequest;
import com.bss.som.dto.DealerDtos.KitBatchRequest;
import com.bss.som.dto.DealerDtos.LeaderboardRow;
import com.bss.som.dto.DealerDtos.OrderStatus;
import com.bss.som.dto.DealerDtos.SaleReceipt;
import com.bss.som.dto.DealerDtos.SaleRequest;
import com.bss.som.dto.DealerDtos.StarterKitView;
import com.bss.som.dto.TelesalesDtos.ConfirmReceipt;
import com.bss.som.dto.TelesalesDtos.ConfirmRequest;
import com.bss.som.dto.TelesalesDtos.DialList;
import com.bss.som.dto.TelesalesDtos.OfferReceipt;
import com.bss.som.dto.TelesalesDtos.OfferRequest;
import com.bss.som.dto.TelesalesDtos.OfferRow;
import com.bss.som.service.DealerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The DEALER CHANNEL's doors. All authenticated; the powers come from
 * facts, not routes: agreements are back-office (no party scope), dealer
 * endpoints require the caller's org to hold an agreement (checked live),
 * and kit activation is any customer's own act.
 */
@RestController
public class DealerController {

    private final DealerService dealers;
    private final com.bss.som.service.TelesalesService telesales;
    private final com.bss.som.security.TenantScope tenantScope;
    private final com.bss.som.security.PartyScope partyScope;

    public DealerController(DealerService dealers, com.bss.som.service.TelesalesService telesales,
            com.bss.som.security.TenantScope tenantScope, com.bss.som.security.PartyScope partyScope) {
        this.dealers = dealers;
        this.telesales = telesales;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /** Back office signs a chain: org + commission per activation. */
    @PostMapping("/dealer/v1/agreement")
    public ResponseEntity<DealerAgreementView> createAgreement(@RequestBody AgreementRequest dto) {
        return ResponseEntity.ok(dealers.createAgreement(dto));
    }

    /** A batch of starter kits for the caller's own store. */
    @PostMapping("/dealer/v1/kits/batch")
    public ResponseEntity<List<StarterKitView>> mintBatch(@RequestBody KitBatchRequest dto) {
        return ResponseEntity.ok(dealers.mintBatch(dto));
    }

    /** The store's kit shelf — available and activated. */
    @GetMapping("/dealer/v1/kits")
    public ResponseEntity<List<StarterKitView>> kits() {
        return ResponseEntity.ok(dealers.myKits());
    }

    /** The counter sale: order on the customer's behalf, dealer-stamped. */
    @PostMapping("/dealer/v1/sell")
    public ResponseEntity<SaleReceipt> sell(@RequestBody SaleRequest dto) {
        return ResponseEntity.ok(dealers.sell(dto));
    }

    /** G4 — the season scoreboard: stores ranked by accrued commission.
     *  A dealer sees the ranking; the wholesale desk sees it too. */
    @GetMapping("/dealer/v1/leaderboard")
    public ResponseEntity<List<LeaderboardRow>> leaderboard() {
        return ResponseEntity.ok(dealers.leaderboard());
    }

    /** The money page: entries + totals, own store only. */
    @GetMapping("/dealer/v1/commission")
    public ResponseEntity<CommissionPage> commission() {
        return ResponseEntity.ok(dealers.myCommission());
    }

    /** The POS asks "did it activate, what did we earn". */
    @GetMapping("/dealer/v1/orders/{productOrderId}")
    public ResponseEntity<OrderStatus> orderStatus(
            @org.springframework.web.bind.annotation.PathVariable("productOrderId") String id) {
        return ResponseEntity.ok(dealers.orderStatus(id));
    }

    /** TELESALES: the call's output is an OFFER — washed against the
     * do-not-call register (fail-closed), binding only on confirmation. */
    @PostMapping("/dealer/v1/telesales/offer")
    public ResponseEntity<OfferReceipt> telesalesOffer(@RequestBody OfferRequest dto) {
        return ResponseEntity.ok(telesales.offer(dto));
    }

    /** THE DIAL LIST: segment members, consent at the source, every
     * number washed — reserved citizens excluded, never listed. */
    @GetMapping("/dealer/v1/telesales/dialList")
    public ResponseEntity<DialList> dialList(
            @org.springframework.web.bind.annotation.RequestParam("segment") String segment) {
        return ResponseEntity.ok(telesales.dialList(segment));
    }

    /** The partner's pipeline: their own offers, whatever became of them. */
    @GetMapping("/dealer/v1/telesales/offers")
    public ResponseEntity<List<OfferRow>> telesalesOffers() {
        return ResponseEntity.ok(telesales.myOffers());
    }

    /** The customer's WRITTEN yes, signed in — only now is the order born. */
    @PostMapping("/telesales/v1/confirm")
    public ResponseEntity<ConfirmReceipt> telesalesConfirm(@RequestBody ConfirmRequest dto) {
        return ResponseEntity.ok(telesales.confirm(tenantScope.currentTenantId(),
                partyScope.scopedPartyId().orElse(null), dto.token()));
    }

    /** The kit comes alive: the CUSTOMER types the code from the box. */
    @PostMapping("/dealer/v1/starterKit/activate")
    public ResponseEntity<KitActivationReceipt> activate(@RequestBody KitActivationRequest dto) {
        return ResponseEntity.ok(dealers.activateKit(dto));
    }
}
