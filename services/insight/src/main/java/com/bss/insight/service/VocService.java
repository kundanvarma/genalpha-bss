package com.bss.insight.service;

import com.bss.insight.entity.CustomerSignal;
import com.bss.insight.entity.SignalClassification;
import com.bss.insight.entity.VocAlert;
import com.bss.insight.events.DomainEventPublisher;
import com.bss.insight.repository.CustomerSignalRepository;
import com.bss.insight.repository.SignalClassificationRepository;
import com.bss.insight.repository.VocAlertRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Voice of Customer, v1 (SI-P4): BATTERY AGGREGATES — per-aspect volume,
 * sentiment mix, this-week-vs-baseline trend, top pain points. This is SQL
 * over verified classifications, not embedding clustering, and the pane says
 * so. Early warning: an aspect whose 7-day negatives run ≥2× its trailing
 * baseline (and ≥ the floor) trips ONE auditable alert per ISO week and puts
 * VocDeviationEvent on the bus — an operator inbox or journey trigger away.
 */
@Service
public class VocService {

    private static final int WINDOW_DAYS = 28;
    private static final int MIN_NEGATIVES = 5;
    private static final double DEVIATION_RATIO = 2.0;

    private final CustomerSignalRepository signals;
    private final SignalClassificationRepository classifications;
    private final VocAlertRepository alerts;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public VocService(CustomerSignalRepository signals,
            SignalClassificationRepository classifications, VocAlertRepository alerts,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.signals = signals;
        this.classifications = classifications;
        this.alerts = alerts;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        String tenant = tenantScope.currentTenantId();
        List<CustomerSignal> recent = signals.findTop100ByTenantIdOrderByReceivedAtDesc(tenant);
        Map<String, SignalClassification> byId = classifications
                .findByTenantIdAndSignalIdIn(tenant, recent.stream().map(CustomerSignal::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        SignalClassification::getSignalId, java.util.function.Function.identity()));
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(WINDOW_DAYS);
        OffsetDateTime weekAgo = OffsetDateTime.now().minusDays(7);

        Map<String, Map<String, Object>> aspects = new LinkedHashMap<>();
        int classified = 0;
        for (CustomerSignal s : recent) {
            SignalClassification c = byId.get(s.getId());
            if (c == null || s.getReceivedAt().isBefore(cutoff)) {
                continue;
            }
            classified++;
            String aspect = c.getAspect() == null ? "other" : c.getAspect();
            Map<String, Object> a = aspects.computeIfAbsent(aspect, k -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("aspect", k);
                fresh.put("total", 0);
                fresh.put("positive", 0);
                fresh.put("neutral", 0);
                fresh.put("negative", 0);
                fresh.put("thisWeek", 0);
                fresh.put("weekNegatives", 0);
                fresh.put("painPoints", new ArrayList<Map<String, Object>>());
                return fresh;
            });
            bump(a, "total");
            bump(a, c.getSentiment() == null ? "neutral" : c.getSentiment());
            boolean thisWeek = !s.getReceivedAt().isBefore(weekAgo);
            if (thisWeek) {
                bump(a, "thisWeek");
                if ("negative".equals(c.getSentiment())) {
                    bump(a, "weekNegatives");
                }
            }
            if (c.getPainPoint() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pains = (List<Map<String, Object>>) a.get("painPoints");
                if (pains.size() < 5) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("painPoint", c.getPainPoint());
                    if (c.getPainImpact() != null) {
                        p.put("impact", c.getPainImpact());
                    }
                    p.put("signalId", s.getId());
                    pains.add(p);
                }
            }
        }
        for (Map<String, Object> a : aspects.values()) {
            // baseline: the prior 3 weeks' negatives as a weekly average
            int negatives = (int) a.get("negative");
            int weekNeg = (int) a.get("weekNegatives");
            double baseline = Math.max((negatives - weekNeg) / 3.0, 0);
            a.put("baselineWeeklyNegatives", round2(baseline));
            a.put("deviating", weekNeg >= MIN_NEGATIVES
                    && weekNeg >= baseline * DEVIATION_RATIO);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", WINDOW_DAYS);
        out.put("classifiedSignals", classified);
        out.put("aspects", aspects.values().stream()
                .sorted((x, y) -> Integer.compare((int) y.get("total"), (int) x.get("total"))).toList());
        out.put("alerts", alerts.findTop20ByTenantIdOrderByCreatedAtDesc(tenant));
        // honesty label: what this pane IS at v1
        out.put("method", "battery-aggregates-v1 (SQL over verified classifications; not embedding clustering)");
        return out;
    }

    /** Hourly + on demand: trip at most one auditable alert per (aspect, week). */
    @Scheduled(initialDelayString = "${bss.insight.voc.initial-delay-ms:180000}",
            fixedDelayString = "${bss.insight.voc.fixed-delay-ms:3600000}")
    @Transactional
    public void scheduledDeviationSweep() {
        try (com.bss.insight.security.TenantContext ignored =
                com.bss.insight.security.TenantContext.actAs("genalpha")) {
            deviationSweep();
        } catch (Exception e) {
            // the sweep must never take the service down
        }
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> deviationSweep() {
        String tenant = tenantScope.currentTenantId();
        String isoWeek = isoWeekNow();
        int fired = 0;
        for (Map<String, Object> a : (List<Map<String, Object>>) summary().get("aspects")) {
            if (!Boolean.TRUE.equals(a.get("deviating"))) {
                continue;
            }
            String aspect = String.valueOf(a.get("aspect"));
            if (alerts.existsByTenantIdAndAspectAndIsoWeek(tenant, aspect, isoWeek)) {
                continue;
            }
            VocAlert alert = new VocAlert();
            alert.setId(UUID.randomUUID().toString());
            alert.setTenantId(tenant);
            alert.setAspect(aspect);
            alert.setIsoWeek(isoWeek);
            alert.setWeekNegatives((int) a.get("weekNegatives"));
            alert.setBaselineAvg(BigDecimal.valueOf((double) a.get("baselineWeeklyNegatives")));
            double baseline = (double) a.get("baselineWeeklyNegatives");
            alert.setRatio(BigDecimal.valueOf(baseline == 0 ? 99
                    : round2((int) a.get("weekNegatives") / baseline)));
            alert.setCreatedAt(OffsetDateTime.now());
            alerts.save(alert);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("aspect", aspect);
            event.put("isoWeek", isoWeek);
            event.put("weekNegatives", alert.getWeekNegatives());
            event.put("baselineWeeklyNegatives", baseline);
            events.publish("VocDeviationEvent", "vocDeviation", event);
            fired++;
        }
        return Map.of("fired", fired, "isoWeek", isoWeek);
    }

    private static void bump(Map<String, Object> m, String key) {
        m.merge(key, 1, (a, b) -> (int) a + (int) b);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String isoWeekNow() {
        OffsetDateTime now = OffsetDateTime.now();
        return now.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                + String.format("%02d", now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
