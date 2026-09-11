package com.bss.usage.client;

/**
 * One way of reading balances from / crediting an Online Charging System,
 * registered under the name tenants.yml selects per operator
 * ({@code ocs-provider}). The router picks the adapter per tenant.
 */
public interface OcsBalanceAdapter extends OcsClient {

    /** The name tenants.yml uses to select this adapter. */
    String name();
}
