package com.bss.payment.client;

import com.bss.payment.dto.VaultMethodRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Vault view (TMF670): resolve a saved method to its token + presentation.
 * The vault's document belongs to another component — it arrives as a tree and
 * is read path by path; what payment SENDS is a record, so the outbound shape
 * is pinned by the round-trip test.
 */
public interface PaymentMethodClient {

    JsonNode resolve(String paymentMethodId);

    /** Save a method INTO the vault (machine) — used to vault a BNPL recurring
     * token the provider just minted. */
    JsonNode save(VaultMethodRequest request);
}
