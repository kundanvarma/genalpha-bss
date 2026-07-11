package com.bss.payment.psp;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Development PSP with Stripe-style test semantics: any card number ending in
 * 0002 is declined, everything else authorizes. Real money never moves.
 */
@Component
public class MockPspAdapter implements PspAdapter {

    @Override
    public Authorization authorize(BigDecimal amount, String currency, Map<String, Object> paymentMethod) {
        // A vault token is a pre-validated reference: the PSP already knows
        // the card behind it. Mock: token pays, label from the stored last4.
        if (paymentMethod != null && paymentMethod.get("token") != null) {
            return new Authorization(true,
                    "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    "bankCard •••• " + paymentMethod.getOrDefault("lastFourDigits", "????"), null);
        }
        String number = String.valueOf(paymentMethod == null ? "" : paymentMethod.getOrDefault("cardNumber", ""))
                .replaceAll("\\s", "");
        if (number.length() < 12) {
            return new Authorization(false, null, null, "invalid card number");
        }
        String last4 = number.substring(number.length() - 4);
        if (number.endsWith("0002")) {
            return new Authorization(false, null, "bankCard •••• " + last4, "card declined");
        }
        return new Authorization(true, "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "bankCard •••• " + last4, null);
    }
}
