package com.bss.communication.service;

import com.bss.communication.security.TenantRegistry;
import com.bss.communication.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * One-click unsubscribe tokens (RFC 8058): an HMAC in the link, so honouring a
 * click needs no login, and only the operator's secret can mint one.
 * Deterministic, so the footer that builds it and the endpoint that verifies it
 * agree without storing anything.
 *
 * Two things were wrong with the first version and both are the same mistake in
 * different clothes — one secret for everybody:
 *
 *  - The secret was deployment-wide with a literal default in this file. In a
 *    source-available repository that means an operator who never set the
 *    property was signing with a value anyone could read, and forging an
 *    unsubscribe for any customer is then arithmetic. The secret is now per
 *    tenant, from the registry, and there is no default in the source: with no
 *    secret configured nothing is minted and nothing verifies.
 *  - The signature covered the party id alone, so a link minted by one operator
 *    verified for another. It now covers the tenant and the party, which also
 *    means a leaked secret stops at its own operator.
 *
 * The migration is the delicate part: links already sit in people's inboxes and
 * must keep working, and an unsubscribe that stops working is the one failure a
 * marketing system may never have. So verification accepts a legacy token —
 * the old party-only form under an explicitly configured legacy secret — and
 * says so in the log each time, which is how an operator learns when the old
 * links have aged out and the legacy secret can go. Nothing mints the old form
 * any more.
 */
@Component
public class UnsubscribeToken {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeToken.class);

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final String deploymentSecret;
    private final String legacySecret;
    private final String baseUrl;

    public UnsubscribeToken(TenantRegistry tenants, TenantScope tenantScope,
            @Value("${bss.communication.unsubscribe-secret:}") String deploymentSecret,
            @Value("${bss.communication.unsubscribe-legacy-secret:}") String legacySecret,
            @Value("${bss.communication.public-base-url:http://localhost:8080}") String baseUrl) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.deploymentSecret = deploymentSecret == null ? "" : deploymentSecret.trim();
        this.legacySecret = legacySecret == null ? "" : legacySecret.trim();
        this.baseUrl = baseUrl;
    }

    /** This operator's own signing secret, or blank when none is configured. */
    private String secretFor(String tenantId) {
        TenantRegistry.TenantEntry entry = tenantId == null ? null : tenants.byId(tenantId);
        String own = entry == null ? null : entry.getUnsubscribeSecret();
        if (own != null && !own.isBlank()) {
            return own.trim();
        }
        return deploymentSecret;
    }

    public String forParty(String partyId) {
        return forParty(tenantScope.currentTenantId(), partyId);
    }

    /** The token in this operator's links: HMAC over tenant and party together. */
    public String forParty(String tenantId, String partyId) {
        String secret = secretFor(tenantId);
        if (secret.isBlank()) {
            throw new IllegalStateException("no unsubscribe secret configured for tenant '" + tenantId
                    + "' — set unsubscribe-secret on the tenant or bss.communication.unsubscribe-secret");
        }
        return hmac(secret, tenantId + "." + partyId);
    }

    public boolean valid(String partyId, String token) {
        if (partyId == null || token == null) {
            return false;
        }
        String tenantId = tenantScope.currentTenantId();
        String secret = secretFor(tenantId);
        if (!secret.isBlank() && constantTimeEquals(hmac(secret, tenantId + "." + partyId), token)) {
            return true;
        }
        // A link that was already in an inbox when the scheme changed. Honoured
        // only while the operator keeps the old secret configured on purpose.
        if (!legacySecret.isBlank() && constantTimeEquals(hmac(legacySecret, partyId), token)) {
            log.warn("honoured a LEGACY unsubscribe link for tenant '{}' — these predate per-tenant "
                    + "signing; retire bss.communication.unsubscribe-legacy-secret once they have aged out",
                    tenantId);
            return true;
        }
        return false;
    }

    /** A one-click unsubscribe URL to drop in a marketing message footer. */
    public String linkFor(String partyId) {
        return baseUrl + "/esp/v1/unsubscribe?p=" + partyId + "&t=" + forParty(partyId);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 24); // 96 bits — plenty for a link token
        } catch (Exception e) {
            throw new IllegalStateException("could not mint unsubscribe token", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
