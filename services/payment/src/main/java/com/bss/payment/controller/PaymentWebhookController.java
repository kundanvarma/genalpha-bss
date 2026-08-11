package com.bss.payment.controller;

import com.bss.payment.api.ApiConstants;
import com.bss.payment.service.PaymentWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PSP webhooks — anonymous at the door, HMAC-verified inside. Per-tenant path so a
 * provider's project points at its own tenant. The authoritative async confirm for
 * a redirect/BNPL session.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/webhook")
public class PaymentWebhookController {

    private final PaymentWebhookService service;

    public PaymentWebhookController(PaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping("/{provider}/{tenantId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String provider,
            @PathVariable String tenantId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "x-payment-signature", required = false) String signature) {
        return ResponseEntity.ok(service.handle(provider, tenantId, body, signature));
    }
}
