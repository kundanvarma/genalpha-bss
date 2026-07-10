package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Payment integration switched off (tests, payment-less deployments). */
@Component
@ConditionalOnProperty(name = "bss.payment.enabled", havingValue = "false")
public class NoopPaymentClient implements PaymentClient {

    @Override
    public String validateAuthorized(String paymentId, String expectedOwnerPartyId, String orderId) {
        return "";
    }

    @Override
    public void capture(String paymentId) {
    }

    @Override
    public void voidPayment(String paymentId) {
    }
}
