package com.bss.payment.psp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real-PSP adapter over Stripe's PaymentIntents API — the "bring your own
 * keys" path. Enabled by {@code bss.payment.psp=stripe} with STRIPE_SECRET_KEY
 * set; otherwise the mock adapter is the bean. Money movement maps cleanly:
 * a PaymentIntent authorizes (manual capture), capture settles it, and a
 * refund reverses it. The idempotency key becomes Stripe's own header, so a
 * retried authorize collapses onto the first PaymentIntent — no double hold.
 *
 * Note: card data would be tokenized client-side (Stripe.js) into a
 * PaymentMethod id in production; this adapter expects a payment-method token
 * on the request, never a raw PAN, matching the vault seam the BSS already uses.
 */
@Component
@ConditionalOnProperty(name = "bss.payment.psp", havingValue = "stripe")
public class StripePspAdapter implements PspAdapter {

    private final RestClient stripe;

    public StripePspAdapter(RestClient.Builder builder,
            @Value("${bss.payment.stripe.base-url:https://api.stripe.com}") String baseUrl,
            @Value("${bss.payment.stripe.secret-key}") String secretKey) {
        this.stripe = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .defaultHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Authorization authorize(BigDecimal amount, String currency,
            Map<String, Object> paymentMethod, String idempotencyKey) {
        String token = paymentMethod == null ? null : String.valueOf(paymentMethod.get("token"));
        if (token == null || "null".equals(token)) {
            return Authorization.declined(null, "a tokenized payment method is required for Stripe");
        }
        String form = "amount=" + minorUnits(amount)
                + "&currency=" + currency.toLowerCase()
                + "&payment_method=" + token
                + "&confirm=true&capture_method=manual"
                + "&automatic_payment_methods[enabled]=true"
                + "&automatic_payment_methods[allow_redirects]=never";
        Map<String, Object> intent = stripe.post().uri("/v1/payment_intents")
                .header("Idempotency-Key", idempotencyKey == null ? "" : idempotencyKey)
                .body(form).retrieve().body(Map.class);
        String status = String.valueOf(intent.get("status"));
        String label = labelOf(intent);
        return switch (status) {
            case "requires_capture" -> Authorization.approved(String.valueOf(intent.get("id")), label);
            case "requires_action" -> Authorization.challenge(
                    nextActionUrl(intent), label);
            default -> Authorization.declined(label, "Stripe status: " + status);
        };
    }

    @Override
    public Capture capture(String authorizationCode, BigDecimal amount, String currency) {
        Map<String, Object> res = stripe.post()
                .uri("/v1/payment_intents/" + authorizationCode + "/capture")
                .body("").retrieve().body(Map.class);
        boolean ok = "succeeded".equals(String.valueOf(res.get("status")));
        return new Capture(ok, authorizationCode, ok ? null : "capture not succeeded");
    }

    @Override
    public Refund refund(String authorizationCode, BigDecimal amount, String currency) {
        Map<String, Object> res = stripe.post().uri("/v1/refunds")
                .body("payment_intent=" + authorizationCode).retrieve().body(Map.class);
        boolean ok = res.get("id") != null;
        return new Refund(ok, ok ? String.valueOf(res.get("id")) : null,
                ok ? null : "refund failed");
    }

    @Override
    public String provider() {
        return "stripe";
    }

    private static long minorUnits(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    @SuppressWarnings("unchecked")
    private static String labelOf(Map<String, Object> intent) {
        Object charges = intent.get("latest_charge");
        return charges == null ? "bankCard" : "bankCard (Stripe)";
    }

    @SuppressWarnings("unchecked")
    private static String nextActionUrl(Map<String, Object> intent) {
        if (intent.get("next_action") instanceof Map<?, ?> action
                && action.get("redirect_to_url") instanceof Map<?, ?> redirect) {
            return String.valueOf(redirect.get("url"));
        }
        return null;
    }
}
