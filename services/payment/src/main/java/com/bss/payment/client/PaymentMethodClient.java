package com.bss.payment.client;

import java.util.Map;

/** Vault view (TMF670): resolve a saved method to its token + presentation. */
public interface PaymentMethodClient {

    Map<String, Object> resolve(String paymentMethodId);
}
