package com.bss.payment.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Deployments without the vault: saved methods simply do not resolve. */
@Component
@ConditionalOnProperty(name = "bss.paymentmethod.enabled", havingValue = "false")
public class NoopPaymentMethodClient implements PaymentMethodClient {

    @Override
    public Map<String, Object> resolve(String paymentMethodId) {
        return null;
    }
}
