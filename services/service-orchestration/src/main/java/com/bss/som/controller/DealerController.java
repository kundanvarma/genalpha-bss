package com.bss.som.controller;

import com.bss.som.service.DealerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> createAgreement(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(dealers.createAgreement(dto));
    }

    /** A batch of starter kits for the caller's own store. */
    @PostMapping("/dealer/v1/kits/batch")
    public ResponseEntity<List<Map<String, Object>>> mintBatch(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(dealers.mintBatch(dto));
    }

    /** The store's kit shelf — available and activated. */
    @GetMapping("/dealer/v1/kits")
    public ResponseEntity<List<Map<String, Object>>> kits() {
        return ResponseEntity.ok(dealers.myKits());
    }

    /** The counter sale: order on the customer's behalf, dealer-stamped. */
    @PostMapping("/dealer/v1/sell")
    public ResponseEntity<Map<String, Object>> sell(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(dealers.sell(dto));
    }

    /** The money page: entries + totals, own store only. */
    @GetMapping("/dealer/v1/commission")
    public ResponseEntity<Map<String, Object>> commission() {
        return ResponseEntity.ok(dealers.myCommission());
    }

    /** The POS asks "did it activate, what did we earn". */
    @GetMapping("/dealer/v1/orders/{productOrderId}")
    public ResponseEntity<Map<String, Object>> orderStatus(
            @org.springframework.web.bind.annotation.PathVariable("productOrderId") String id) {
        return ResponseEntity.ok(dealers.orderStatus(id));
    }

    /** TELESALES: the call's output is an OFFER — washed against the
     * do-not-call register (fail-closed), binding only on confirmation. */
    @PostMapping("/dealer/v1/telesales/offer")
    public ResponseEntity<Map<String, Object>> telesalesOffer(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(telesales.offer(dto));
    }

    /** THE DIAL LIST: segment members, consent at the source, every
     * number washed — reserved citizens excluded, never listed. */
    @GetMapping("/dealer/v1/telesales/dialList")
    public ResponseEntity<Map<String, Object>> dialList(
            @org.springframework.web.bind.annotation.RequestParam("segment") String segment) {
        return ResponseEntity.ok(telesales.dialList(segment));
    }

    /** The partner's pipeline: their own offers, whatever became of them. */
    @GetMapping("/dealer/v1/telesales/offers")
    public ResponseEntity<List<Map<String, Object>>> telesalesOffers() {
        return ResponseEntity.ok(telesales.myOffers());
    }

    /** The customer's WRITTEN yes, signed in — only now is the order born. */
    @PostMapping("/telesales/v1/confirm")
    public ResponseEntity<Map<String, Object>> telesalesConfirm(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(telesales.confirm(tenantScope.currentTenantId(),
                partyScope.scopedPartyId().orElse(null),
                dto.get("token") == null ? null : String.valueOf(dto.get("token"))));
    }

    /** The kit comes alive: the CUSTOMER types the code from the box. */
    @PostMapping("/dealer/v1/starterKit/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(dealers.activateKit(dto));
    }
}
