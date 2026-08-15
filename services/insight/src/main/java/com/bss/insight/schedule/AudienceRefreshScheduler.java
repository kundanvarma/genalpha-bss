package com.bss.insight.schedule;

import com.bss.insight.entity.Audience;
import com.bss.insight.repository.AudienceRepository;
import com.bss.insight.security.TenantContext;
import com.bss.insight.security.TenantRegistry;
import com.bss.insight.service.AudienceService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps materialized audiences warm: on a cadence, re-freeze the stalest ones so
 * a snapshot never drifts far from live. Deliberately OBSERVABLE and CONTROLLABLE
 * so a continuously-running feature is never a black box:
 * <ul>
 *   <li>BOUNDED by design — at most {@code max-per-run} audiences per tenant per
 *       sweep, stalest first — so it can't fan out or accumulate memory.</li>
 *   <li>METERED — run/refresh/error counts, last duration, and live JVM heap are
 *       on the status endpoint AND in Prometheus (Grafana), so ops can SEE
 *       whether it correlates with a memory climb.</li>
 *   <li>CONTROLLABLE — pause/resume at runtime and trigger a manual run, so ops
 *       can stop it WITHOUT restarting the service (restart stays available).</li>
 * </ul>
 */
@Component
public class AudienceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(AudienceRefreshScheduler.class);

    private final AudienceService audienceService;
    private final AudienceRepository audiences;
    private final TenantRegistry tenants;
    private final AtomicBoolean enabled;
    private final int maxPerRun;
    private final long intervalMs;

    private final AtomicLong totalRuns = new AtomicLong();
    private final AtomicLong totalRefreshed = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong lastDurationMs = new AtomicLong();
    private final AtomicLong lastRefreshed = new AtomicLong();
    private volatile OffsetDateTime lastRunAt;

    public AudienceRefreshScheduler(AudienceService audienceService, AudienceRepository audiences,
            TenantRegistry tenants, MeterRegistry meters,
            @Value("${bss.insight.refresh.enabled:true}") boolean enabled,
            @Value("${bss.insight.refresh.interval-ms:300000}") long intervalMs,
            @Value("${bss.insight.refresh.max-per-run:25}") int maxPerRun) {
        this.audienceService = audienceService;
        this.audiences = audiences;
        this.tenants = tenants;
        this.enabled = new AtomicBoolean(enabled);
        this.intervalMs = intervalMs;
        this.maxPerRun = maxPerRun;
        // Prometheus/Grafana visibility (JVM heap is already exported by actuator).
        meters.gauge("insight.refresh.enabled", this.enabled, b -> b.get() ? 1 : 0);
        meters.gauge("insight.refresh.total_runs", totalRuns, AtomicLong::doubleValue);
        meters.gauge("insight.refresh.total_refreshed", totalRefreshed, AtomicLong::doubleValue);
        meters.gauge("insight.refresh.total_errors", totalErrors, AtomicLong::doubleValue);
        meters.gauge("insight.refresh.last_duration_ms", lastDurationMs, AtomicLong::doubleValue);
        meters.gauge("insight.refresh.last_refreshed", lastRefreshed, AtomicLong::doubleValue);
    }

    /** The timer path — respects the pause switch. */
    @Scheduled(fixedDelayString = "${bss.insight.refresh.interval-ms:300000}",
            initialDelayString = "${bss.insight.refresh.interval-ms:300000}")
    public void scheduled() {
        if (enabled.get()) {
            runSweep();
        }
    }

    /** Re-materialize the stalest materialized audiences, per tenant, capped. */
    public int runSweep() {
        long t = System.currentTimeMillis();
        int refreshed = 0;
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            String tenantId = tenant.getId();
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                for (Audience a : audiences.findByTenantIdAndMaterializedAtIsNotNullOrderByMaterializedAtAsc(
                        tenantId, PageRequest.of(0, maxPerRun))) {
                    try {
                        audienceService.refresh(a.getId());
                        refreshed++;
                        totalRefreshed.incrementAndGet();
                    } catch (Exception e) {
                        totalErrors.incrementAndGet();
                        log.warn("auto-refresh failed for audience {} (tenant {}): {}", a.getId(), tenantId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("auto-refresh sweep failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        lastDurationMs.set(System.currentTimeMillis() - t);
        lastRefreshed.set(refreshed);
        lastRunAt = OffsetDateTime.now();
        totalRuns.incrementAndGet();
        return refreshed;
    }

    public void pause() {
        enabled.set(false);
        log.info("audience auto-refresh PAUSED by operator");
    }

    public void resume() {
        enabled.set(true);
        log.info("audience auto-refresh RESUMED by operator");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** What ops sees: scheduler activity + live JVM heap, in one place. */
    public Map<String, Object> status() {
        java.lang.management.MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled.get());
        m.put("intervalMs", intervalMs);
        m.put("maxPerRun", maxPerRun);
        m.put("totalRuns", totalRuns.get());
        m.put("totalRefreshed", totalRefreshed.get());
        m.put("totalErrors", totalErrors.get());
        m.put("lastRunAt", lastRunAt);
        m.put("lastDurationMs", lastDurationMs.get());
        m.put("lastRefreshed", lastRefreshed.get());
        m.put("heapUsedMb", heap.getUsed() / (1024 * 1024));
        m.put("heapMaxMb", heap.getMax() / (1024 * 1024));
        return m;
    }
}
