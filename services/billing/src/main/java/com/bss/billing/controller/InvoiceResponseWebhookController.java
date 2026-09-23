package com.bss.billing.controller;

import com.bss.billing.security.TenantContext;
import com.bss.billing.security.TenantRegistry;
import com.bss.billing.service.BillDistributionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.bss.billing.dto.WebhookRefused;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The door the DISTRIBUTION PARTNER knocks on with the buyer's answer:
 * POST /distribution/v1/response takes a Peppol Invoice Response (UBL
 * ApplicationResponse) and lands it on the delivery ledger. The shared
 * secret is the SAME per-tenant distribution token we use to talk to
 * the partner — one credential per relationship, and the token IS the
 * tenant: it picks whose ledger the answer can touch, never the payload.
 */
@RestController
public class InvoiceResponseWebhookController {

    private final TenantRegistry tenants;
    private final BillDistributionService distribution;

    public InvoiceResponseWebhookController(TenantRegistry tenants,
            BillDistributionService distribution) {
        this.tenants = tenants;
        this.distribution = distribution;
    }

    @PostMapping(value = "/distribution/v1/response", consumes = {"application/xml", "text/xml"})
    public ResponseEntity<?> respond(@RequestBody String xml,
            @RequestHeader(value = "X-Distribution-Token", required = false) String token) {
        TenantRegistry.TenantEntry tenant = tenantOf(token);
        if (tenant == null) {
            return ResponseEntity.status(401).body(new WebhookRefused("unknown distribution credential"));
        }
        try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
            return ResponseEntity.ok(distribution.applyInvoiceResponse(tenant.getId(), xml));
        }
    }

    /**
     * Which tenant's credential this is. A tenant that has configured no
     * distribution token has no door at all — it is skipped rather than
     * matched on a blank — and the comparison is constant time, so the door
     * does not leak the token one character at a time.
     */
    private TenantRegistry.TenantEntry tenantOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        TenantRegistry.TenantEntry found = null;
        for (TenantRegistry.TenantEntry entry : tenants.getRegistry()) {
            String configured = entry.getBillDistributionToken();
            if (configured == null || configured.isBlank()) {
                continue;
            }
            // no early exit: every configured tenant is compared, every time
            if (MessageDigest.isEqual(presented, configured.getBytes(StandardCharsets.UTF_8)) && found == null) {
                found = entry;
            }
        }
        return found;
    }
}
