package com.bss.cart.controller;

import com.bss.cart.exception.NotFoundException;
import com.bss.cart.security.TenantRegistry;
import com.bss.cart.security.TenantScope;
import com.bss.cart.service.AcpCheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The Agentic Commerce Protocol checkout endpoints, spec-shaped:
 *
 *   POST /acp/checkout_sessions               open a session from items
 *   GET  /acp/checkout_sessions/{id}          authoritative session state
 *   POST /acp/checkout_sessions/{id}          update items / buyer
 *   POST /acp/checkout_sessions/{id}/complete apply payment, place the order
 *   POST /acp/checkout_sessions/{id}/cancel   cancel while open
 *
 * A session before complete is a guest basket: its random id is the bearer
 * secret, exactly like a TMF663 guest cart. Complete demands the buyer's
 * delegated token. The gateway's per-tenant switch (off|discovery|full) is
 * authoritative; the check here is defense in depth.
 */
@RestController
@RequestMapping("/acp/checkout_sessions")
public class AcpCheckoutController {

    public static final String API_VERSION = "2026-04-17";

    private final AcpCheckoutService service;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public AcpCheckoutController(AcpCheckoutService service, TenantRegistry tenants,
            TenantScope tenantScope) {
        this.service = service;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        requireFull();
        return respond(HttpStatus.CREATED, service.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        requireFull();
        return respond(HttpStatus.OK, service.get(id));
    }

    @PostMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireFull();
        return respond(HttpStatus.OK, service.update(id, request));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        requireFull();
        return respond(HttpStatus.OK, service.complete(id, request, idempotencyKey, authorization));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        requireFull();
        return respond(HttpStatus.OK, service.cancel(id));
    }

    /** Defense in depth behind the gateway's authoritative gate: checkout
     * needs 'full' — a 'discovery' or 'off' tenant has no sessions here. */
    private void requireFull() {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        String mode = tenant == null || tenant.getAgentCommerce() == null
                ? "off" : tenant.getAgentCommerce();
        if (!"full".equals(mode)) {
            throw new NotFoundException("no agentic checkout surface here");
        }
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).header("API-Version", API_VERSION).body(body);
    }
}
