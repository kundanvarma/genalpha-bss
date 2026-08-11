package com.bss.document.controller;

import com.bss.document.api.ApiConstants;
import com.bss.document.service.ContentWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * External CMS/DAM webhooks — anonymous at the door, HMAC-verified inside (the
 * shared secret is the auth). Per-tenant path so a CMS project points at its own
 * tenant. Sanity today; the {provider} segment is where P4's connectors slot in.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/webhook")
public class ContentWebhookController {

    private final ContentWebhookService service;

    public ContentWebhookController(ContentWebhookService service) {
        this.service = service;
    }

    @PostMapping("/{provider}/{tenantId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String provider,
            @PathVariable String tenantId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "sanity-webhook-signature", required = false) String signature) {
        if (!"sanity".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no webhook handler for provider " + provider);
        }
        return ResponseEntity.ok(service.handleSanity(tenantId, body, signature));
    }
}
