package com.bss.usage.security;

/**
 * Explicit tenant override for code that runs outside any request — Kafka
 * consumers act as the event's tenant, sweepers as the SYSTEM tenant that
 * row-level security lets span all rows. Request threads never need this:
 * TenantScope reads the verified issuer. Always used try-with-resources so
 * the thread never leaks a tenant into unrelated work.
 */
public final class TenantContext implements AutoCloseable {

    /** Matches the escape hatch in the row-level-security policies. */
    public static final String SYSTEM = "__system__";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static TenantContext actAs(String tenantId) {
        CURRENT.set(tenantId);
        return new TenantContext();
    }

    public static TenantContext actAsSystem() {
        return actAs(SYSTEM);
    }

    public static String current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
