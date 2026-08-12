package com.bss.payment.client;

import java.util.Map;

/** Vault view (TMF670): resolve a saved method to its token + presentation. */
public interface PaymentMethodClient {

    Map<String, Object> resolve(String paymentMethodId);

    /** Save a method INTO the vault (machine) — used to vault a BNPL recurring
     * token the provider just minted. */
    Map<String, Object> save(Map<String, Object> dto);
}
