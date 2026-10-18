package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * An operator exists: its identity, brand basics, storefront host, and how long
 * the minting took — and its first staff login. The password appears exactly
 * once, here, for hand-over (#321); it is never logged or stored by the BSS.
 */
@JsonPropertyOrder({"id", "name", "locale", "currency", "storefrontHost", "seconds", "staffUsername", "staffPassword"})
public record OnboardReceipt(String id, String name, String locale, String currency, String storefrontHost,
        long seconds,
        @JsonInclude(JsonInclude.Include.NON_NULL) String staffUsername,
        @JsonInclude(JsonInclude.Include.NON_NULL) String staffPassword) {

    /** The receipt without a login to hand over (a re-read, or a test). */
    public OnboardReceipt(String id, String name, String locale, String currency, String storefrontHost,
            long seconds) {
        this(id, name, locale, currency, storefrontHost, seconds, null, null);
    }
}
