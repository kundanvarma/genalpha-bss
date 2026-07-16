package com.bss.billing.controller;

import com.bss.billing.security.TenantContext;
import com.bss.billing.security.TenantRegistry;
import com.bss.billing.service.RemittanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The door the BANK knocks on: POST /bank/v1/remittance takes an ISO
 * 20022 camt.054 credit notification. The shared secret rides its own
 * header (an Authorization bearer would be eaten by the OIDC filter),
 * and the token IS the tenant: each tenant configures its own bank
 * secret, so the credential picks whose bills the money can touch —
 * never the payload.
 */
@RestController
public class RemittanceWebhookController {

    private final TenantRegistry tenants;
    private final RemittanceService remittance;

    public RemittanceWebhookController(TenantRegistry tenants, RemittanceService remittance) {
        this.tenants = tenants;
        this.remittance = remittance;
    }

    @PostMapping(value = "/bank/v1/remittance", consumes = {"application/xml", "text/xml"})
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody String xml,
            @RequestHeader(value = "X-Bank-Token", required = false) String token) {
        TenantRegistry.TenantEntry tenant = tenantOf(token);
        if (tenant == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unknown bank credential"));
        }
        try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
            return ResponseEntity.ok(remittance.ingest(tenant.getId(), xml));
        }
    }

    private TenantRegistry.TenantEntry tenantOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (TenantRegistry.TenantEntry entry : tenants.getRegistry()) {
            if (token.equals(entry.getBankToken())) {
                return entry;
            }
        }
        return null;
    }
}
