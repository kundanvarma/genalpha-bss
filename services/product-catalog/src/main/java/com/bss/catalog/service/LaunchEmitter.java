package com.bss.catalog.service;

import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.repository.ProductOfferingRepository;
import com.bss.catalog.security.TenantContext;
import com.bss.catalog.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * L3 — launch day fires itself: when a Launched offering's validFor window
 * OPENS, one ProductOfferingLaunchedEvent goes out (announced_at is the
 * dedupe), so launch journeys and campaigns need no human to press go.
 * Visibility never depends on this tick — the window is enforced at query
 * time; this emitter only announces.
 */
@Component
public class LaunchEmitter {

    private static final Logger log = LoggerFactory.getLogger(LaunchEmitter.class);

    private final ProductOfferingRepository offerings;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final LaunchGovernanceService governance;

    public LaunchEmitter(ProductOfferingRepository offerings, DomainEventPublisher events,
            TenantRegistry tenants, LaunchGovernanceService governance) {
        this.offerings = offerings;
        this.events = events;
        this.tenants = tenants;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${bss.catalog.launch-tick-ms:60000}")
    @Transactional
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                for (ProductOffering offering : offerings.findByTenantId(tenant.getId())) {
                    governance.tick(offering, now); // approvals expire, dated holds lift
                    if (LaunchGovernanceService.HELD.equals(offering.getGovernanceState())) {
                        continue; // a held offer never announces itself
                    }
                    if (offering.getAnnouncedAt() != null
                            || !LifecyclePolicy.launched(offering.getLifecycleStatus())
                            || offering.getValidFrom() == null
                            || offering.getValidFrom().isAfter(now)
                            || (offering.getValidTo() != null && !offering.getValidTo().isAfter(now))) {
                        continue;
                    }
                    offering.setAnnouncedAt(now);
                    offerings.save(offering);
                    events.publish("ProductOfferingLaunchedEvent", "productOffering", Map.of(
                            "id", offering.getId(), "name", offering.getName(),
                            "validFrom", offering.getValidFrom().toString()), tenant.getId());
                    log.info("launched: '{}' window opened — the event fires the launch-day machinery",
                            offering.getName());
                }
            } catch (RuntimeException e) {
                log.warn("launch tick skipped for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
