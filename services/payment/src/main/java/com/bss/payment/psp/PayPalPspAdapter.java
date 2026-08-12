package com.bss.payment.psp;

import com.bss.payment.entity.PspConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * PayPal as a redirect wallet — the SECOND redirect provider, proving the seam is
 * not Klarna-special. createSession → a PayPal Orders v2 order with an approve
 * link; confirm → read the order's approval state; capture/refund → the order's
 * capture/refund. Unlike Klarna (BNPL), PayPal settles to the merchant at
 * capture, so the revenue subledger books it as cash (not a BNPL receivable).
 * Shaped to the Orders v2 flow, proven against a mock-paypal; real credentials as
 * config (base_url + secret_ref), the real-account proof an opt-in follow-up.
 */
@Component
public class PayPalPspAdapter implements RedirectPspAdapter {

    private static final Logger log = LoggerFactory.getLogger(PayPalPspAdapter.class);

    private final RestClient.Builder builder;

    public PayPalPspAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "paypal";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Session createSession(PspConfig cfg, BigDecimal amount, String currency, String returnUrl) {
        Map<String, Object> resp = client(cfg).post().uri("/v2/checkout/orders")
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount, "currency", currency == null ? "EUR" : currency,
                        "returnUrl", returnUrl == null ? "" : returnUrl))
                .retrieve().body(Map.class);
        if (resp == null || resp.get("id") == null) {
            throw new IllegalStateException("PayPal createOrder returned no order");
        }
        log.info("paypal order {} created for {} {}", resp.get("id"), amount, currency);
        return new Session(str(resp.get("id")), str(resp.get("approve_url")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation confirm(PspConfig cfg, String sessionId) {
        Map<String, Object> resp = client(cfg).get().uri("/v2/checkout/orders/{id}", sessionId)
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "PayPal", "order not found");
        }
        boolean approved = Boolean.TRUE.equals(resp.get("approved"));
        return new Confirmation(approved, num(resp.get("amount")), str(resp.get("currency")),
                str(resp.get("authorization_code")), "PayPal", approved ? null : str(resp.get("decline_reason")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement capture(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/v2/checkout/orders/{id}/capture", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount == null ? 0 : amount, "currency", currency == null ? "EUR" : currency))
                .retrieve().body(Map.class);
        boolean ok = resp != null && Boolean.TRUE.equals(resp.get("captured"));
        log.info("paypal capture order {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("capture_id")),
                ok ? null : "capture rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement refund(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/v2/checkout/orders/{id}/refund", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", amount == null ? 0 : amount, "currency", currency == null ? "EUR" : currency))
                .retrieve().body(Map.class);
        boolean ok = resp != null && Boolean.TRUE.equals(resp.get("refunded"));
        log.info("paypal refund order {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("refund_id")),
                ok ? null : "refund rejected");
    }

    private RestClient client(PspConfig cfg) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl() == null ? "https://api-m.paypal.com" : cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + key);
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
