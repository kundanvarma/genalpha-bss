package com.bss.payment.client;

import com.bss.payment.dto.VaultMethodRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deployments without the vault: saved methods simply do not resolve. */
@Component
@ConditionalOnProperty(name = "bss.paymentmethod.enabled", havingValue = "false")
public class NoopPaymentMethodClient implements PaymentMethodClient {

    @Override
    public JsonNode resolve(String paymentMethodId) {
        return null;
    }

    @Override
    public JsonNode save(VaultMethodRequest request) {
        throw new IllegalStateException("no payment-method vault in this deployment");
    }
}
