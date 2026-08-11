package com.bss.fulfilment.controller;

import com.bss.fulfilment.api.ApiConstants;
import com.bss.fulfilment.client.CarrierRouter;
import com.bss.fulfilment.security.TenantScope;
import com.bss.fulfilment.service.CarrierConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator's carrier menu (per tenant) + pickup-point search. GET lists /
 * searches (ordering:read); PUT/DELETE edit the menu (ordering:write). One binding
 * per (tenant, carrier); the API key is a secret-ref, never the value.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/carrier")
public class CarrierController {

    private final CarrierConfigService service;
    private final CarrierRouter router;
    private final TenantScope tenantScope;

    public CarrierController(CarrierConfigService service, CarrierRouter router, TenantScope tenantScope) {
        this.service = service;
        this.router = router;
        this.tenantScope = tenantScope;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.listForCurrentTenant());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.upsert(dto));
    }

    @DeleteMapping("/{carrier}")
    public ResponseEntity<Void> delete(@PathVariable String carrier) {
        service.delete(carrier);
        return ResponseEntity.noContent().build();
    }

    /** Pickup points near a postcode for a configured carrier (empty if none). */
    @GetMapping("/pickupPoints")
    public ResponseEntity<List<Map<String, Object>>> pickupPoints(
            @RequestParam String carrier, @RequestParam String postcode) {
        return ResponseEntity.ok(router.pickupPoints(tenantScope.currentTenantId(), carrier, postcode));
    }

    /** The shopper's delivery menu for a postcode (home + pickup points) — anonymous,
     * so guest checkout can offer it. Empty menu → the built-in home default. */
    @GetMapping("/deliveryOptions")
    public ResponseEntity<List<Map<String, Object>>> deliveryOptions(
            @RequestParam(required = false) String postcode) {
        return ResponseEntity.ok(router.deliveryOptions(tenantScope.currentTenantId(), postcode));
    }
}
