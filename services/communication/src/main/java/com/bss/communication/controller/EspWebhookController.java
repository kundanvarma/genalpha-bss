package com.bss.communication.controller;

import com.bss.communication.service.EspReceiptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The door the tenant's ESP knocks on. POST /esp/v1/event takes a batch
 * of delivery events (SendGrid Event Webhook shape); auth is per event —
 * the batch's bearer token must be the named tenant's own ESP key.
 * Always 200: webhooks that error get retried forever by providers, and
 * a poisoned event must not wedge the queue.
 */
@RestController
public class EspWebhookController {

    private final EspReceiptService receipts;

    public EspWebhookController(EspReceiptService receipts) {
        this.receipts = receipts;
    }

    /** The key rides its own header (like SendGrid's signature header): an
     * Authorization bearer would be eaten by the OIDC filter as a bad JWT. */
    @PostMapping("/esp/v1/event")
    public ResponseEntity<Map<String, Object>> events(
            @RequestBody List<Map<String, Object>> events,
            @RequestHeader(value = "X-Esp-Token", required = false) String token) {
        return ResponseEntity.ok(Map.of("accepted", receipts.accept(events, token)));
    }

    /** The tenant's do-not-email ledger (staff: communication:read). */
    @GetMapping("/esp/v1/suppression")
    public ResponseEntity<List<Map<String, Object>>> suppression() {
        return ResponseEntity.ok(receipts.suppressionList());
    }
}
