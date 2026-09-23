package com.bss.fulfilment.controller;

import com.bss.fulfilment.api.ApiConstants;
import com.bss.fulfilment.dto.CarrierEvent;
import com.bss.fulfilment.dto.ShippingOrderView;
import com.bss.fulfilment.dto.ShippingPatch;
import com.bss.fulfilment.dto.WorkOrderView;
import com.bss.fulfilment.dto.WorkPatch;
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

/** TMF700 shippingOrder + TMF697 workOrder — the warehouse/installer face. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class FulfilmentController {

    private final FulfilmentService service;

    public FulfilmentController(FulfilmentService service) {
        this.service = service;
    }

    @GetMapping("/shippingOrder")
    public ResponseEntity<List<ShippingOrderView>> shippingOrders() {
        return ResponseEntity.ok(service.listShipping());
    }

    @GetMapping("/shippingOrder/{id}")
    public ResponseEntity<ShippingOrderView> shippingOrder(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.shippingById(id));
    }

    @PatchMapping("/shippingOrder/{id}")
    public ResponseEntity<ShippingOrderView> patchShipping(@PathVariable("id") String id,
            @RequestBody ShippingPatch dto) {
        return ResponseEntity.ok(service.patchShipping(id, dto));
    }

    @GetMapping("/workOrder")
    public ResponseEntity<List<WorkOrderView>> workOrders() {
        return ResponseEntity.ok(service.listWork());
    }

    @GetMapping("/workOrder/{id}")
    public ResponseEntity<WorkOrderView> workOrder(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.workById(id));
    }

    @PatchMapping("/workOrder/{id}")
    public ResponseEntity<WorkOrderView> patchWork(@PathVariable("id") String id,
            @RequestBody WorkPatch dto) {
        return ResponseEntity.ok(service.patchWork(id, dto));
    }

    /**
     * The carrier's delivery callback (C2). Unauthenticated — the carrier is not
     * a BSS identity; it carries its own tenant + shippingOrder refs and only
     * moves a parcel forward. (A production seam would verify a carrier
     * signature/HMAC here; the mock is trusted on the internal network.)
     */
    @PostMapping("/carrierEvent")
    public ResponseEntity<Void> carrierEvent(@RequestBody CarrierEvent event) {
        service.onCarrierEvent(event);
        return ResponseEntity.accepted().build();
    }
}
