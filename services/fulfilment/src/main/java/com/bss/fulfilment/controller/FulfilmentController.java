package com.bss.fulfilment.controller;

import com.bss.fulfilment.api.ApiConstants;
import com.bss.fulfilment.service.FulfilmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** TMF700 shippingOrder + TMF697 workOrder — the warehouse/installer face. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class FulfilmentController {

    private final FulfilmentService service;

    public FulfilmentController(FulfilmentService service) {
        this.service = service;
    }

    @GetMapping("/shippingOrder")
    public ResponseEntity<List<Map<String, Object>>> shippingOrders() {
        return ResponseEntity.ok(service.listShipping());
    }

    @GetMapping("/shippingOrder/{id}")
    public ResponseEntity<Map<String, Object>> shippingOrder(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.shippingById(id));
    }

    @PatchMapping("/shippingOrder/{id}")
    public ResponseEntity<Map<String, Object>> patchShipping(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchShipping(id, dto));
    }

    @GetMapping("/workOrder")
    public ResponseEntity<List<Map<String, Object>>> workOrders() {
        return ResponseEntity.ok(service.listWork());
    }

    @GetMapping("/workOrder/{id}")
    public ResponseEntity<Map<String, Object>> workOrder(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.workById(id));
    }

    @PatchMapping("/workOrder/{id}")
    public ResponseEntity<Map<String, Object>> patchWork(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchWork(id, dto));
    }

    /**
     * The carrier's delivery callback (C2). Unauthenticated — the carrier is not
     * a BSS identity; it carries its own tenant + shippingOrder refs and only
     * moves a parcel forward. (A production seam would verify a carrier
     * signature/HMAC here; the mock is trusted on the internal network.)
     */
    @PostMapping("/carrierEvent")
    public ResponseEntity<Void> carrierEvent(@RequestBody Map<String, Object> event) {
        service.onCarrierEvent(event);
        return ResponseEntity.accepted().build();
    }
}
