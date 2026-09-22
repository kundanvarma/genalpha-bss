package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** An operator exists: its identity, brand basics, storefront host, and how long the minting took. */
@JsonPropertyOrder({"id", "name", "locale", "currency", "storefrontHost", "seconds"})
public record OnboardReceipt(String id, String name, String locale, String currency, String storefrontHost,
        long seconds) {
}
