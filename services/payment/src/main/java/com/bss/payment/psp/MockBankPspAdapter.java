package com.bss.payment.psp;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A second always-on card PSP ("mockbank") — the backup/alternate acquirer used to
 * exercise orchestration: routing (a currency rule sends a charge here) and
 * failover (when the primary acquirer is unreachable, the charge lands here). It
 * approves any structurally-valid card — notably it does NOT share the primary's
 * 0009 outage — so a charge the primary couldn't reach still settles. A hard
 * decline (0002) is still declined, so a decline never spuriously "recovers" on
 * the backup. Real money never moves.
 */
@Component
public class MockBankPspAdapter implements PspAdapter {

    @Override
    public Authorization authorize(BigDecimal amount, String currency,
            Map<String, Object> paymentMethod, String idempotencyKey) {
        if (paymentMethod != null && paymentMethod.get("token") != null) {
            return Authorization.approved(authCode(),
                    "bankCard •••• " + paymentMethod.getOrDefault("lastFourDigits", "????") + " (mockbank)");
        }
        String number = String.valueOf(paymentMethod == null ? ""
                : paymentMethod.getOrDefault("cardNumber", "")).replaceAll("\\s", "");
        if (number.length() < 12) {
            return Authorization.declined(null, "invalid card number");
        }
        String label = "bankCard •••• " + number.substring(number.length() - 4) + " (mockbank)";
        if (number.endsWith("0002")) {
            return Authorization.declined(label, "card declined");
        }
        return Authorization.approved(authCode(), label);
    }

    @Override
    public Capture capture(String authorizationCode, BigDecimal amount, String currency) {
        return new Capture(true, "MBCAP-" + shortId(), null);
    }

    @Override
    public Refund refund(String authorizationCode, BigDecimal amount, String currency) {
        return new Refund(true, "MBREF-" + shortId(), null);
    }

    @Override
    public String provider() {
        return "mockbank";
    }

    private static String authCode() {
        return "MBAUTH-" + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
