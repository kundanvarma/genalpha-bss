package com.bss.intelligence.workforce;

import com.bss.intelligence.events.DomainEventPublisher;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The autoscaler's eyes: a periodic sweep that publishes each tenant's
 * staffing truth as Prometheus gauges —
 *
 *   bss_workforce_backlog_depth{tenant=…}
 *   bss_workforce_active_workers{tenant=…}
 *   bss_workforce_surge{tenant=…}           (0 | 1)
 *
 * — and, on the surge boundary, a domain event (WorkforceSurgeEvent /
 * WorkforceSurgeRelievedEvent) so martech-grade consumers can react too.
 * KEDA/HPA scales worker replicas on the gauges; the crew ceiling in the
 * claim path caps whatever the scaler does. TickGuard keeps the events
 * exactly-once-ish across replicas (gauges are per-replica and harmless).
 */
@Component
public class StaffingSweep {

    private static final Logger log = LoggerFactory.getLogger(StaffingSweep.class);

    private final WorkforceKpiService kpis;
    private final TenantRegistry tenants;
    private final DomainEventPublisher events;
    private final com.bss.intelligence.tick.TickGuard tickGuard;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> backlogGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> workerGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> surgeGauges = new ConcurrentHashMap<>();
    private final Map<String, Boolean> surging = new ConcurrentHashMap<>();

    public StaffingSweep(WorkforceKpiService kpis, TenantRegistry tenants,
            DomainEventPublisher events, com.bss.intelligence.tick.TickGuard tickGuard,
            MeterRegistry meters) {
        this.kpis = kpis;
        this.tenants = tenants;
        this.events = events;
        this.tickGuard = tickGuard;
        this.meters = meters;
    }

    @Scheduled(initialDelayString = "${bss.workforce.staffing-initial-delay-ms:45000}",
            fixedDelayString = "${bss.workforce.staffing-sweep-ms:60000}")
    public void sweep() {
        if (!tickGuard.claim("workforce-staffing", java.time.Duration.ofMinutes(2))) {
            return;
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    sweepTenant(tenant.getId());
                } catch (Exception e) {
                    // one tenant's unreachable backlog never blinds the rest
                    log.warn("staffing sweep skipped tenant {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("workforce-staffing");
        }
    }

    private void sweepTenant(String tenantId) {
        Map<String, Object> s = kpis.staffing();
        long backlog = ((Number) s.get("backlogDepth")).longValue();
        long active = ((Number) s.get("activeWorkers")).longValue();
        boolean surge = Boolean.TRUE.equals(s.get("surge"));

        gauge(backlogGauges, "bss_workforce_backlog_depth", tenantId).set(backlog);
        gauge(workerGauges, "bss_workforce_active_workers", tenantId).set(active);
        gauge(surgeGauges, "bss_workforce_surge", tenantId).set(surge ? 1 : 0);

        Boolean was = surging.put(tenantId, surge);
        if (surge && !Boolean.TRUE.equals(was)) {
            events.publish("WorkforceSurgeEvent", "workforce", s, tenantId);
            log.info("workforce SURGE for {}: backlog {} over {} active worker(s)",
                    tenantId, backlog, active);
        } else if (!surge && Boolean.TRUE.equals(was)) {
            events.publish("WorkforceSurgeRelievedEvent", "workforce", s, tenantId);
            log.info("workforce surge RELIEVED for {}", tenantId);
        }
    }

    private AtomicLong gauge(Map<String, AtomicLong> store, String name, String tenantId) {
        return store.computeIfAbsent(tenantId, t -> {
            AtomicLong value = new AtomicLong();
            meters.gauge(name, Tags.of("tenant", t), value);
            return value;
        });
    }
}
