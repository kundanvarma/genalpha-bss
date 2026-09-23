package com.bss.som.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The credential on the two wholesale doors.
 *
 * <p>Both callers are foreign systems — an access seeker's BSS placing a MEF
 * Sonata order, and a fibre owner's OSS calling back when the line goes live —
 * so neither can hold a token minted in this deployment's IdP. The identity is
 * a <b>per-tenant shared secret</b> ({@code wholesale-order-secret} in the
 * registry), used two ways:
 *
 * <ul>
 * <li><b>The order door</b> is signed like a PSP webhook:
 *     {@code x-wholesale-signature: t=<epoch millis>,v1=<base64url>} over
 *     {@code "<t>.<raw body>"}, constant-time compared, inside a freshness
 *     window. Because the signature verifies against the secret of the tenant
 *     the {@code X-Tenant-Id} header names, that header stops being a free
 *     choice: only the operator that issued the seeker its secret can be named.
 * <li><b>The activation callback</b> has nowhere to put a header — the owner's
 *     OSS posts to the bare URL we handed it in the order — so the credential
 *     is a segment of that URL: a token bound to the order id and derived from
 *     the owning tenant's secret. Knowing the order UUID is no longer enough.
 * </ul>
 *
 * <p>Nothing here logs a secret, a signature or a derived token.
 */
@Component
public class WholesaleDoorAuth {

    /** The header an access seeker signs its Sonata order with. */
    public static final String SIGNATURE_HEADER = "x-wholesale-signature";

    private final TenantRegistry tenants;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long toleranceMs;

    public WholesaleDoorAuth(TenantRegistry tenants,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.wholesale.signature-tolerance-ms:300000}") long toleranceMs) {
        this.tenants = tenants;
        this.toleranceMs = toleranceMs;
    }

    /** Verify the seeker's signature over the raw body, then answer the parsed order. */
    public <T> T verifiedOrder(String tenantId, byte[] rawBody, String signatureHeader, Class<T> type) {
        verify(secretOf(tenantId), rawBody, signatureHeader);
        try {
            return mapper.readValue(rawBody == null || rawBody.length == 0
                    ? "{}".getBytes(StandardCharsets.UTF_8) : rawBody, type);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wholesale order body is not JSON");
        }
    }

    /**
     * What an access seeker puts in {@link #SIGNATURE_HEADER} when it orders
     * from the named operator: {@code t=<now>,v1=HMAC(secret, "<t>.<body>")}
     * over exactly the bytes that go on the wire.
     */
    public String signature(String tenantId, byte[] rawBody) {
        long t = System.currentTimeMillis();
        String v1 = hmac(secretOf(tenantId),
                t + "." + new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8));
        return "t=" + t + ",v1=" + v1;
    }

    /** The credential segment of the callback URL we hand the owner's OSS. */
    public String callbackToken(String tenantId, String orderId) {
        return hmac(secretOf(tenantId), "wholesale-callback." + orderId);
    }

    /** True when this path segment is the callback token for this order. */
    public void requireCallbackToken(String tenantId, String orderId, String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("missing callback token");
        }
        if (!equalsConstantTime(callbackToken(tenantId, orderId), token)) {
            throw unauthorized("callback token mismatch");
        }
    }

    /** Whether this tenant sells wholesale access at all (has a secret). */
    public boolean configured(String tenantId) {
        TenantRegistry.TenantEntry t = tenantId == null ? null : tenants.byId(tenantId);
        String secret = t == null ? null : t.getWholesaleOrderSecret();
        return secret != null && !secret.isBlank();
    }

    /* ------------------------------------------------------------ internals */

    private String secretOf(String tenantId) {
        TenantRegistry.TenantEntry t = tenantId == null || tenantId.isBlank() ? null : tenants.byId(tenantId);
        if (t == null) {
            throw unauthorized("unknown tenant");
        }
        String secret = t.getWholesaleOrderSecret();
        if (secret == null || secret.isBlank()) {
            throw unauthorized("this operator has no wholesale credential");
        }
        return secret;
    }

    private void verify(String secret, byte[] body, String header) {
        if (header == null || header.isBlank()) {
            throw unauthorized("missing signature");
        }
        String ts = null;
        String provided = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                ts = kv[1];
            } else if (kv[0].equals("v1")) {
                provided = kv[1];
            }
        }
        if (ts == null || provided == null) {
            throw unauthorized("malformed signature");
        }
        long sent;
        try {
            sent = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            throw unauthorized("malformed signature timestamp");
        }
        if (toleranceMs > 0 && Math.abs(System.currentTimeMillis() - sent) > toleranceMs) {
            throw unauthorized("signature outside the freshness window");
        }
        String expected = hmac(secret, ts + "." + new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
        if (!equalsConstantTime(expected, provided)) {
            throw unauthorized("signature mismatch");
        }
    }

    private static boolean equalsConstantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed: " + e.getMessage(), e);
        }
    }

    private static ResponseStatusException unauthorized(String why) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wholesale door not authorized (" + why + ")");
    }
}
