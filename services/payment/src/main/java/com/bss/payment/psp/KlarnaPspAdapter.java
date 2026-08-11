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
