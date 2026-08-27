package com.bss.party.service;

import com.bss.party.security.TenantContext;
import com.bss.party.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The re-sync clock: every tenant polls the registry feed on its own cursor,
 * inside its own TenantContext (RLS-safe — no system-tenant sweep needed,
 * each tenant only ever touches its own rows). A registry outage costs
 * nothing: the client answers an empty feed and the cursor stands still.
 * Disable per deployment with {@code bss.registry.sync-enabled=false}
 * (tests do — they drive the sync through the trigger endpoint instead).
 */
@Component
@ConditionalOnProperty(name = "bss.registry.sync-enabled", havingValue = "true", matchIfMissing = true)
public class RegistrySyncWorker {

    private static final Logger log = LoggerFactory.getLogger(RegistrySyncWorker.class);

    private final RegistrySyncService sync;
    private final TenantRegistry tenants;

    public RegistrySyncWorker(RegistrySyncService sync, TenantRegistry tenants) {
        this.sync = sync;
        this.tenants = tenants;
    }

    @Scheduled(fixedDelayString = "${bss.registry.sync-interval-ms:30000}",
            initialDelayString = "${bss.registry.sync-initial-delay-ms:30000}")
    public void poll() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                sync.syncCurrentTenant();
            } catch (Exception e) {
                log.warn("registry sync failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
