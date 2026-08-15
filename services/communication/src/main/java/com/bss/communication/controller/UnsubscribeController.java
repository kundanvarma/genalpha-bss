package com.bss.communication.controller;

import com.bss.communication.security.TenantContext;
import com.bss.communication.security.TenantScope;
import com.bss.communication.service.MarketingPreferenceService;
import com.bss.communication.service.UnsubscribeToken;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-click unsubscribe (RFC 8058-style): the link in every marketing message.
 * Public — no login — but the token is an HMAC of the party id, so it only works
 * for the customer it was minted for and can't be forged. Honours the opt-out and
 * returns a plain confirmation page. Tenant is resolved from the gateway's
 * X-Tenant-Id (hostname), since there's no authenticated issuer here.
 */
@RestController
public class UnsubscribeController {

    private final MarketingPreferenceService prefs;
    private final UnsubscribeToken tokens;
    private final TenantScope tenantScope;

    public UnsubscribeController(MarketingPreferenceService prefs, UnsubscribeToken tokens, TenantScope tenantScope) {
        this.prefs = prefs;
        this.tokens = tokens;
        this.tenantScope = tenantScope;
    }

    @GetMapping(value = "/esp/v1/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam("p") String partyId,
            @RequestParam("t") String token) {
        if (!tokens.valid(partyId, token)) {
            return ResponseEntity.badRequest().body(page("This unsubscribe link is invalid or has expired."));
        }
        // No authenticated issuer on a public click — act as the header/default tenant.
        try (TenantContext ignored = TenantContext.actAs(tenantScope.currentTenantId())) {
            prefs.setOptOut(partyId, true, null);
        }
        return ResponseEntity.ok(page("You've been unsubscribed from marketing messages. "
                + "You'll still get essential service and billing notices."));
    }

    private static String page(String message) {
        return "<!doctype html><meta charset=\"utf-8\"><title>Unsubscribe</title>"
                + "<div style=\"font:16px/1.5 -apple-system,sans-serif;max-width:32rem;margin:4rem auto;"
                + "padding:0 1rem;color:#20262b\"><h2 style=\"color:#147673\">Marketing preferences</h2><p>"
                + message + "</p></div>";
    }
}
