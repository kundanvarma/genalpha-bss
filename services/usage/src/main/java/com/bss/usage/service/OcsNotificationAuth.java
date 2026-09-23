package com.bss.usage.service;

import com.bss.usage.client.OcsSettings;
import com.bss.usage.security.TenantRegistry;
import com.bss.usage.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The credential on the OCS → BSS door ({@code /internal/ocs/**}).
 *
 * <p>The caller is an Online Charging System — a foreign product on the
 * operator's own network (Ericsson, Huawei, Matrixx, SigScale, the bundled
 * stand-in). It cannot hold a BSS machine token, so the identity is a
 * <b>per-tenant shared secret</b>, exactly as the PSP webhook does it:
 * header {@code x-ocs-signature: t=<epoch millis>,v1=<base64url>} where the
 * signature is HMAC-SHA256(secret, {@code "<t>.<raw body>"}), compared in
 * constant time, with a freshness window so a captured call cannot be
 * replayed for ever. The secret is the tenant's
 * {@code ocs-notify-secret} config ref in the registry.
 *
 * <p>The tenant is taken from the signed body (or, for the SigScale hub, from
 * the signed callback path) and is only believed <i>because</i> the signature
 * over that body verifies against that tenant's own secret. A tenant with no
 * secret configured, or a tenant nobody has registered, has a shut door: 401.
 * Nothing here logs a secret, a signature or a derived token.
 */
@Service
public class OcsNotificationAuth {

    /** The header the OCS signs its notification with. */
    public static final String SIGNATURE_HEADER = "x-ocs-signature";

    private final OcsSettings settings;
    private final TenantRegistry tenants;
    private final TenantScope scope;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long toleranceMs;

    public OcsNotificationAuth(OcsSettings settings, TenantRegistry tenants, TenantScope scope,
            @Value("${bss.ocs.notify-signature-tolerance-ms:300000}") long toleranceMs) {
        this.settings = settings;
        this.tenants = tenants;
        this.scope = scope;
        this.toleranceMs = toleranceMs;
    }

    /**
     * Verify the signature over the raw body with the secret of the tenant the
     * body names (the deployment's default tenant when it names none), then
     * answer the parsed notification. 401 on anything less.
     */
    public <T> T verified(byte[] rawBody, String signatureHeader, Class<T> type) {
        JsonNode body = readBody(rawBody);
        String named = body.path("tenantId").asText(null);
        String tenantId = named == null || named.isBlank() ? scope.currentTenantId() : named;
        verify(tenantId, rawBody, signatureHeader);
        return parse(rawBody, type);
    }

    /**
     * Verify the signature over the raw body with the named tenant's secret —
     * the shape for a door whose tenant rides the path rather than the body.
     */
    public JsonNode verifiedForTenant(String tenantId, byte[] rawBody, String signatureHeader) {
        verify(tenantId, rawBody, signatureHeader);
        return readBody(rawBody);
    }

    /**
     * The opaque path segment a callback URL carries when the caller cannot set
     * a header — a TM Forum hub registration is a bare URL, so the credential
     * has to be in it. Deterministic per tenant, so re-registering the hub is
     * idempotent, and derived from (never equal to) the tenant's secret.
     */
    public String callbackToken(String tenantId) {
        return hmac(secretOf(tenantId), "ocs-callback." + tenantId);
    }

    /** Check the path's callback token against this tenant's, then answer the body. */
    public JsonNode acceptCallbackToken(String tenantId, String token, byte[] rawBody) {
        if (token == null || token.isBlank()) {
            throw unauthorized("missing callback token");
        }
        if (!equalsConstantTime(callbackToken(tenantId), token)) {
            throw unauthorized("callback token mismatch");
        }
        return readBody(rawBody);
    }

    /* ------------------------------------------------------------ internals */

    private String secretOf(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || tenants.byId(tenantId) == null) {
            throw unauthorized("unknown tenant");
        }
        OcsSettings.Binding binding = settings.forTenant(tenantId);
        if (!binding.notificationsSigned()) {
            throw unauthorized("no OCS notification secret for this tenant");
        }
        return binding.notifySecret();
    }

    private void verify(String tenantId, byte[] body, String header) {
        String secret = secretOf(tenantId);
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

    private JsonNode readBody(byte[] rawBody) {
        try {
            return mapper.readTree(rawBody == null || rawBody.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OCS notification body is not JSON");
        }
    }

    private <T> T parse(byte[] rawBody, Class<T> type) {
        try {
            return mapper.readValue(rawBody == null || rawBody.length == 0
                    ? "{}".getBytes(StandardCharsets.UTF_8) : rawBody, type);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OCS notification body is not JSON");
        }
    }

    private static ResponseStatusException unauthorized(String why) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OCS notification not authorized (" + why + ")");
    }
}
