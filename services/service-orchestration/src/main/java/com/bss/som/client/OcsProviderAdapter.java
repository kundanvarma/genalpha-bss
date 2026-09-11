package com.bss.som.client;

/**
 * One way of talking to an Online Charging System, registered under a name
 * the tenant fleet file selects per operator ({@code ocs-provider}): the
 * generic {@code http} shape the bundled mock and vendor gateways expose, or
 * a product-specific adapter such as {@code sigscale}. The router picks the
 * adapter per tenant; adapters never pick themselves.
 */
public interface OcsProviderAdapter extends OcsProvisioningClient {

    /** The name tenants.yml uses to select this adapter. */
    String name();

    /** Whether this tenant has a reachable configuration for the adapter
     * (a base URL); false = every call is a logged no-op. */
    boolean enabledFor(String tenantId);
}
