package com.bss.payment.service;

import com.bss.payment.dto.PaymentDto;
import com.bss.payment.dto.WebhookReceipt;
import com.bss.payment.entity.PspConfig;
import com.bss.payment.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * PSP webhooks — the authoritative async confirmation for a redirect/BNPL session
 * (a customer who closes the tab before returning must still get confirmed).
 * Anonymous at the door, HMAC-verified inside (the per-tenant shared secret is the
 * auth); runs in the named tenant's RLS scope. Confirm is idempotent, so the
 * webhook and the return leg never double-book.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final PspConfigService configs;
    private final PaymentService payments;
    private final ObjectMapper mapper = new ObjectMapper();
    /** How far a webhook's signed timestamp may sit from our clock, either way. */
    private final long toleranceMillis;

    public PaymentWebhookService(PspConfigService configs, PaymentService payments,
            @Value("${bss.payment.webhook.tolerance-millis:300000}") long toleranceMillis) {
        this.configs = configs;
        this.payments = payments;
        this.toleranceMillis = toleranceMillis;
    }

    public WebhookReceipt handle(String provider, String tenantId, byte[] rawBody, String signatureHeader) {
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            PspConfig cfg = configs.forTenantAndProvider(tenantId, provider).orElse(null);
            String secret = cfg == null ? null : env(cfg.getWebhookSecretRef());
            if (secret == null) {
                throw unauthorized("no webhook secret for tenant/provider");
            }
            verify(rawBody, signatureHeader, secret);
            JsonNode body = readBody(rawBody);
            String sessionId = firstNonBlank(body.path("sessionId").asText(null), body.path("session_id").asText(null));
            if (sessionId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook body needs a sessionId");
            }
            PaymentDto dto = payments.confirmSession(tenantId, provider, sessionId);
            log.info("psp webhook {} tenant={} session={} -> payment {}", provider, tenantId, sessionId, dto.getId());
            return new WebhookReceipt(provider, sessionId, dto.getId(), dto.getStatus());
        }
    }

    /** Signature: header "t=<ms>,v1=<base64url>", sig = HMAC-SHA256(secret, "<t>.<body>").
     *
     * The timestamp is signed, so it cannot be edited without the secret — but a
     * signature stays valid forever unless someone looks at how old it is. Anyone
     * who captured one confirmed webhook could replay it: the same body, the same
     * signature, accepted again. Confirm is idempotent, so a replay of the SAME
     * session books nothing twice; what it buys an attacker is the right to keep
     * a confirmation alive indefinitely, and to replay it in a different order
     * than the PSP sent it. A signature older than the tolerance is refused.
     *
     * Future timestamps are refused by the same window, so a captured signature
     * cannot be post-dated to extend its own life. The window is the clock skew
     * we tolerate between the PSP and us, not a business setting: five minutes
     * is the same figure the major PSPs use for their own verifiers.
     */
    private void verify(byte[] body, String header, String secret) {
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
            throw unauthorized("malformed signature");
        }
        long age = Math.abs(System.currentTimeMillis() - sent);
        if (age > toleranceMillis) {
            // Deliberately vague to the caller; the reason goes to the log only.
            log.warn("psp webhook signature outside the freshness window: {} ms old (tolerance {} ms)",
                    age, toleranceMillis);
            throw unauthorized("stale signature");
        }
        String expected = hmac(secret, ts + "." + new String(body, StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("signature mismatch");
        }
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
            return mapper.readTree(rawBody == null || rawBody.length == 0 ? "{}".getBytes() : rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook body is not JSON");
        }
    }

    private static String env(String ref) {
        return ref == null || ref.isBlank() ? null : System.getenv(ref);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static ResponseStatusException unauthorized(String why) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "webhook not authorized (" + why + ")");
    }
}
