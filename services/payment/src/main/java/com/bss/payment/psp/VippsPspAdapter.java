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
 * Vipps MobilePay as a redirect provider — the wallet a Norwegian checkout is
 * expected to offer. Shaped to the ePayment API: createSession → a payment with
 * a redirectUrl the customer approves in the app; confirm → read the payment's
 * state (AUTHORIZED). Amounts ride in MINOR units (øre), the Vipps contract.
 * Proven against a mock-vipps; real credentials as config (base_url +
 * secret_ref = the Ocp-Apim-Subscription-Key env var name).
 */
@Component
public class VippsPspAdapter implements RedirectPspAdapter {

    private static final Logger log = LoggerFactory.getLogger(VippsPspAdapter.class);

    private final RestClient.Builder builder;

    public VippsPspAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "vipps";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Session createSession(PspConfig cfg, BigDecimal amount, String currency, String returnUrl) {
        Map<String, Object> resp = client(cfg).post().uri("/epayment/v1/payments")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "amount", Map.of("currency", currency == null ? "NOK" : currency, "value", ore(amount)),
                        "userFlow", "WEB_REDIRECT",
                        "returnUrl", returnUrl == null ? "" : returnUrl,
                        "paymentDescription", "Order checkout"))
                .retrieve().body(Map.class);
        if (resp == null || resp.get("reference") == null) {
            throw new IllegalStateException("Vipps createPayment returned no reference");
        }
        log.info("vipps payment {} created for {} {}", resp.get("reference"), amount, currency);
        return new Session(str(resp.get("reference")), str(resp.get("redirectUrl")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation confirm(PspConfig cfg, String sessionId) {
        Map<String, Object> resp = client(cfg).get().uri("/epayment/v1/payments/{ref}", sessionId)
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "Vipps", "payment not found");
        }
        boolean approved = "AUTHORIZED".equals(str(resp.get("state")));
        Map<String, Object> amt = resp.get("amount") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new Confirmation(approved, kroner(amt.get("value")), str(amt.get("currency")),
                str(resp.get("pspReference")), "Vipps", approved ? null : str(resp.get("state")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement capture(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/epayment/v1/payments/{ref}/capture", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("modificationAmount",
                        Map.of("currency", currency == null ? "NOK" : currency, "value", ore(amount))))
                .retrieve().body(Map.class);
        boolean ok = resp != null && "CAPTURED".equals(str(resp.get("state")));
        log.info("vipps capture {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("pspReference")),
                ok ? null : "capture rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Settlement refund(PspConfig cfg, String sessionId, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/epayment/v1/payments/{ref}/refund", sessionId)
                .header("Content-Type", "application/json")
                .body(Map.of("modificationAmount",
                        Map.of("currency", currency == null ? "NOK" : currency, "value", ore(amount))))
                .retrieve().body(Map.class);
        boolean ok = resp != null && "REFUNDED".equals(str(resp.get("state")));
        log.info("vipps refund {} -> {}", sessionId, ok);
        return new Settlement(ok, resp == null ? null : str(resp.get("pspReference")),
                ok ? null : "refund rejected");
    }

    @Override
    @SuppressWarnings("unchecked")
    public TokenGrant tokenize(PspConfig cfg, String sessionId) {
        // Vipps recurring = an agreement the customer approves once; charges ride it.
        Map<String, Object> resp = client(cfg).post().uri("/recurring/v3/agreements")
                .header("Content-Type", "application/json")
                .body(Map.of("paymentReference", sessionId))
                .retrieve().body(Map.class);
        if (resp == null || resp.get("agreementId") == null) {
            return null;
        }
        log.info("vipps payment {} -> recurring agreement {}", sessionId, resp.get("agreementId"));
        return new TokenGrant(str(resp.get("agreementId")), "Vipps (avtale)");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Confirmation chargeToken(PspConfig cfg, String token, BigDecimal amount, String currency) {
        Map<String, Object> resp = client(cfg).post().uri("/recurring/v3/agreements/{id}/charges", token)
                .header("Content-Type", "application/json")
                .body(Map.of("amount", Map.of("currency", currency == null ? "NOK" : currency,
                        "value", ore(amount))))
                .retrieve().body(Map.class);
        if (resp == null) {
            return new Confirmation(false, null, null, null, "Vipps", "charge failed");
        }
        boolean approved = "CHARGED".equals(str(resp.get("state")));
        Map<String, Object> amt = resp.get("amount") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        log.info("vipps agreement {} charged -> {}", token, approved);
        return new Confirmation(approved, kroner(amt.get("value")), str(amt.get("currency")),
                str(resp.get("chargeId")), "Vipps (avtale)", approved ? null : str(resp.get("state")));
    }

    private RestClient client(PspConfig cfg) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl() == null ? "https://api.vipps.no" : cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("Ocp-Apim-Subscription-Key", key);
        }
        return b.build();
    }

    /** Vipps amounts are integer minor units: 99.00 NOK -> 9900 øre. */
    private static long ore(BigDecimal amount) {
        return amount == null ? 0
                : amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal kroner(Object minor) {
        return minor == null ? null
                : new BigDecimal(String.valueOf(minor)).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
