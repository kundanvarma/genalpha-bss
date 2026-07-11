package com.bss.payment.psp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Development PSP with Stripe-style test semantics, close enough to exercise
 * the real lifecycle: a card ending 0002 is declined, 3155 triggers a strong-
 * customer-authentication challenge (the BankID/3-D Secure path), everything
 * else authorizes. Capture and refund succeed. Real money never moves.
 */
@Component
@ConditionalOnProperty(name = "bss.payment.psp", havingValue = "mock", matchIfMissing = true)
public class MockPspAdapter implements PspAdapter {

    @Override
    public Authorization authorize(BigDecimal amount, String currency,
            Map<String, Object> paymentMethod, String idempotencyKey) {
        // A vault token is a pre-validated reference: the PSP already knows
        // the card behind it, so no fresh challenge is needed.
        if (paymentMethod != null && paymentMethod.get("token") != null) {
            return Authorization.approved(authCode(),
                    "bankCard •••• " + paymentMethod.getOrDefault("lastFourDigits", "????"));
        }
        String number = String.valueOf(paymentMethod == null ? ""
                : paymentMethod.getOrDefault("cardNumber", "")).replaceAll("\\s", "");
        if (number.length() < 12) {
            return Authorization.declined(null, "invalid card number");
        }
        String last4 = number.substring(number.length() - 4);
        String label = "bankCard •••• " + last4;
        if (number.endsWith("0002")) {
            return Authorization.declined(label, "card declined");
        }
        if (number.endsWith("3155")) {
            // The SCA path: the channel must complete the challenge and retry.
            return Authorization.challenge("/psp/mock/sca-challenge/" + idempotencyKey, label);
        }
        return Authorization.approved(authCode(), label);
    }

    @Override
    public Capture capture(String authorizationCode, BigDecimal amount, String currency) {
        return new Capture(true, "CAP-" + shortId(), null);
    }

    @Override
    public Refund refund(String authorizationCode, BigDecimal amount, String currency) {
        return new Refund(true, "REF-" + shortId(), null);
    }

    @Override
    public String provider() {
        return "mock";
    }

    private static String authCode() {
        return "AUTH-" + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
