package com.bss.payment.psp;

import com.bss.payment.entity.PspConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * MMG (Mobile Money Guyana) as a redirect wallet — the wallet a Guyanese
 * checkout is expected to offer (works on every network; agents nationwide;
 * the rail people already pay GPL, GWI and their ISP with). Shaped as a plain
 * wallet-redirect contract: create a payment, send the customer to approve
 * with the wallet PIN, confirm by reading the payment's status, capture and
 * refund by reference. MMG's Merchant/Biller API is partner-gated, so the
 * paths here are the mock's; the real contract replaces them in this one
 * class. Amounts are WHOLE Guyana dollars (no minor units since 1992).
 * Credential = the env var named by secret_ref, sent as a bearer token.
 */
@Component
public class MmgPspAdapter implements RedirectPspAdapter {

    private static final Logger log = LoggerFactory.getLogger(MmgPspAdapter.class);

    private final RestClient.Builder builder;

    public MmgPspAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "mmg";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Session createSession(PspConfig cfg, BigDecimal amount, String currency, String returnUrl) {
        Map<String, Object> resp = client(cfg).post().uri("/v1/payments")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "amount", Map.of("currency", currency == null ? "GYD" : currency, "value", dollars(amount)),
                        "returnUrl", returnUrl == null ? "" : returnUrl,
                        "reference", "Order checkout"))
                .retrieve().body(Map.class);
        if (resp == null || resp.get("paymentId") == null) {
            throw new IllegalStateException("MMG createPayment returned no paymentId");
        }
        log.info("mmg payment {} created for {} {}", resp.get("paymentId"), amount, currency);
        return new Session(str(resp.get("paymentId")), str(resp.get("redirectUrl")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation confirm(PspConfig cfg, String sessionId) {
        Map<String, Object> resp = client(cfg).get().uri("/v1/payments/{id}", sessionId)
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "MMG", "payment not found");
        }
        String status = str(resp.get("status"));
        boolean approved = "APPROVED".equals(status) || "CAPTURED".equals(status);
        Map<String, Object> amt = resp.get("amount") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new Confirmation(approved, amt.get("value") == null ? null : new BigDecimal(String.valueOf(amt.get("value"))),
                str(amt.get("currency")), str(resp.get("transactionId")), "MMG", approved ? null : status);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement capture(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/v1/payments/{id}/capture", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", Map.of("currency", currency == null ? "GYD" : currency, "value", dollars(amount))))
                .retrieve().body(Map.class);
        boolean ok = resp != null && "CAPTURED".equals(str(resp.get("status")));
        log.info("mmg capture {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("transactionId")), ok ? null : "capture rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement refund(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/v1/payments/{id}/refund", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", Map.of("currency", currency == null ? "GYD" : currency, "value", dollars(amount))))
                .retrieve().body(Map.class);
        boolean ok = resp != null && "REFUNDED".equals(str(resp.get("status")));
        log.info("mmg refund {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("transactionId")), ok ? null : "refund rejected");
    }

    private RestClient client(PspConfig cfg) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl() == null ? "https://api.mmg.gy" : cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + key);
        }
        return b.build();
    }

    /** Guyana dollars have no minor unit in circulation: amounts travel as whole numbers. */
    private static long dollars(BigDecimal amount) {
        return amount == null ? 0 : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
