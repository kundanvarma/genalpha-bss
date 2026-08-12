package com.bss.payment.controller;

import com.bss.payment.api.ApiConstants;
import com.bss.payment.service.PspConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator's PSP menu (per tenant). GET lists (read); PUT/DELETE edit the
 * menu (write). One binding per (tenant, provider); the API key is a secret-ref,
 * never the value. A tenant with no rows charges through the global env PSP.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/paymentProvider")
public class PspConfigController {

    private final PspConfigService service;

    public PspConfigController(PspConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.listForCurrentTenant());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.upsert(dto));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> delete(@PathVariable String provider) {
        service.delete(provider);
        return ResponseEntity.noContent().build();
    }

    /** Test connection — a REACHABILITY probe, never a money call: a configured
     * base URL is pinged (GET /health with a short timeout); an in-process
     * adapter (mock/mockbank, or no base URL) reports so honestly. */
    @org.springframework.web.bind.annotation.PostMapping("/{provider}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String provider) {
        return ResponseEntity.ok(service.testConnection(provider));
    }
}
