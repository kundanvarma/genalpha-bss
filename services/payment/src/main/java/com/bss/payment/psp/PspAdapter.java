package com.bss.payment.psp;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The payment service provider boundary. Vendor-neutral by design: the dev
 * deployment uses the mock adapter; a Stripe/Adyen adapter implements this
 * same interface without touching the service.
 */
public interface PspAdapter {

    /**
     * Attempts to authorize the amount against the given payment method.
     * Card details live only in this call — never stored.
     */
    Authorization authorize(BigDecimal amount, String currency, Map<String, Object> paymentMethod);

    record Authorization(boolean approved, String authorizationCode, String methodLabel, String declineReason) {
    }
}
