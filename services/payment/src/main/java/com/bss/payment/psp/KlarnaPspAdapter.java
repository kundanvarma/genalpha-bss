package com.bss.payment.psp;

import com.bss.payment.entity.PspConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Klarna (BNPL) as a redirect provider. createSession → the Payments session with
 * a redirect/approve URL; confirm → read the session's authorization state. Shaped
 * to Klarna's session flow; proven against a mock-klarna, real credentials as
 * config (base_url + secret_ref), the real-container proof an opt-in follow-up.
 */
@Component
public class KlarnaPspAdapter implements RedirectPspAdapter {

    private static final Logger log = LoggerFactory.getLogger(KlarnaPspAdapter.class);

    private final RestClient.Builder builder;

    public KlarnaPspAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "klarna";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Session createSession(PspConfig cfg, BigDecimal amount, String currency, String returnUrl) {
        Map<String, Object> resp = client(cfg).post().uri("/payments/v1/sessions")
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount, "currency", currency == null ? "EUR" : currency,
                        "returnUrl", returnUrl == null ? "" : returnUrl))
                .retrieve().body(Map.class);
        if (resp == null || resp.get("session_id") == null) {
            throw new IllegalStateException("Klarna createSession returned no session");
        }
        log.info("klarna session {} created for {} {}", resp.get("session_id"), amount, currency);
        return new Session(str(resp.get("session_id")), str(resp.get("redirect_url")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation confirm(PspConfig cfg, String sessionId) {
        Map<String, Object> resp = client(cfg).get().uri("/payments/v1/sessions/{id}", sessionId)
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "Klarna", "session not found");
        }
        boolean approved = Boolean.TRUE.equals(resp.get("approved"));
        return new Confirmation(approved, num(resp.get("amount")), str(resp.get("currency")),
                str(resp.get("authorization_code")), "Klarna", approved ? null : str(resp.get("decline_reason")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement capture(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/payments/v1/sessions/{id}/capture", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount == null ? 0 : amount, "currency", currency == null ? "EUR" : currency))
                .retrieve().body(Map.class);
        boolean ok = resp != null && Boolean.TRUE.equals(resp.get("captured"));
        log.info("klarna capture session {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("capture_id")),
                ok ? null : "capture rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement refund(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/payments/v1/sessions/{id}/refund", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount == null ? 0 : amount, "currency", currency == null ? "EUR" : currency))
                .retrieve().body(Map.class);
        boolean ok = resp != null && Boolean.TRUE.equals(resp.get("refunded"));
        log.info("klarna refund session {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("refund_id")),
                ok ? null : "refund rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public TokenGrant tokenize(PspConfig cfg, String sessionId) {
        Map<String, Object> resp = client(cfg).post().uri("/payments/v1/sessions/{id}/tokenize", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of())
                .retrieve().body(Map.class);
        if (resp == null || resp.get("recurring_token") == null) {
            return null;
        }
        log.info("klarna session {} tokenized for recurring", sessionId);
        return new TokenGrant(str(resp.get("recurring_token")), "Klarna (saved)");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation chargeToken(PspConfig cfg, String token, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/payments/v1/tokens/{token}/charge", token)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount == null ? 0 : amount,
                        "currency", currency == null ? "EUR" : currency))
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "Klarna", "token charge failed");
        }
        boolean approved = Boolean.TRUE.equals(resp.get("approved"));
        log.info("klarna token charge {} -> {}", token, approved);
        return new Confirmation(approved, num(resp.get("amount")), str(resp.get("currency")),
                str(resp.get("charge_id")), "Klarna (saved)",
                approved ? null : str(resp.get("decline_reason")));
    }

    private RestClient client(PspConfig cfg) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl() == null ? "https://api.klarna.com" : cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("Authorization", "Basic " + key);
        }
        return b.build();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal num(Object o) {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }
}
