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

    public DealerController(DealerService dealers) {
        this.dealers = dealers;
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

    /** The kit comes alive: the CUSTOMER types the code from the box. */
    @PostMapping("/dealer/v1/starterKit/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(dealers.activateKit(dto));
    }
}
