package com.bss.insight.service;

import com.bss.insight.dto.VocSummary;
import com.bss.insight.dto.VocSweepReceipt;
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
    private final com.bss.insight.signal.AlertNotifier notifier;
    private final TenantScope tenantScope;

    public VocService(CustomerSignalRepository signals,
            SignalClassificationRepository classifications, VocAlertRepository alerts,
            DomainEventPublisher events, com.bss.insight.signal.AlertNotifier notifier,
            TenantScope tenantScope) {
        this.signals = signals;
        this.classifications = classifications;
        this.alerts = alerts;
        this.events = events;
        this.notifier = notifier;
        this.tenantScope = tenantScope;
    }

    /** One aspect's counters while the window is being walked; frozen into {@link VocSummary.Aspect} at the end. */
    private static final class AspectDraft {
        final String aspect;
        int total, positive, neutral, negative, thisWeek, weekNegatives;
        final List<VocSummary.PainPoint> painPoints = new ArrayList<>();

        AspectDraft(String aspect) {
            this.aspect = aspect;
        }

        void bump(String sentiment) {
            switch (sentiment == null ? "neutral" : sentiment) {
                case "positive" -> positive++;
                case "negative" -> negative++;
                default -> neutral++; // an out-of-vocabulary sentiment counts as neutral
            }
        }

        VocSummary.Aspect frozen() {
            // baseline: the prior 3 weeks' negatives as a weekly average
            double baseline = Math.max((negative - weekNegatives) / 3.0, 0);
            return new VocSummary.Aspect(aspect, total, positive, neutral, negative, thisWeek, weekNegatives, painPoints,
                    round2(baseline), weekNegatives >= MIN_NEGATIVES && weekNegatives >= baseline * DEVIATION_RATIO);
        }
    }

    @Transactional(readOnly = true)
    public VocSummary summary() {
        String tenant = tenantScope.currentTenantId();
        List<CustomerSignal> recent = signals.findTop100ByTenantIdOrderByReceivedAtDesc(tenant);
        Map<String, SignalClassification> byId = classifications
                .findByTenantIdAndSignalIdIn(tenant, recent.stream().map(CustomerSignal::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        SignalClassification::getSignalId, java.util.function.Function.identity()));
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(WINDOW_DAYS);
        OffsetDateTime weekAgo = OffsetDateTime.now().minusDays(7);

        Map<String, AspectDraft> aspects = new LinkedHashMap<>();
        int classified = 0;
        for (CustomerSignal s : recent) {
            SignalClassification c = byId.get(s.getId());
            if (c == null || s.getReceivedAt().isBefore(cutoff)) {
                continue;
            }
            classified++;
            String aspect = c.getAspect() == null ? "other" : c.getAspect();
            AspectDraft a = aspects.computeIfAbsent(aspect, AspectDraft::new);
            a.total++;
            a.bump(c.getSentiment());
            boolean thisWeek = !s.getReceivedAt().isBefore(weekAgo);
            if (thisWeek) {
                a.thisWeek++;
                if ("negative".equals(c.getSentiment())) {
                    a.weekNegatives++;
                }
            }
            if (c.getPainPoint() != null && a.painPoints.size() < 5) {
                a.painPoints.add(new VocSummary.PainPoint(c.getPainPoint(), c.getPainImpact(), s.getId()));
            }
        }
        List<VocSummary.Aspect> rows = aspects.values().stream().map(AspectDraft::frozen)
                .sorted((x, y) -> Integer.compare(y.total(), x.total())).toList();
        // honesty label: what this pane IS at v1
        return new VocSummary(WINDOW_DAYS, classified, rows, alerts.findTop20ByTenantIdOrderByCreatedAtDesc(tenant),
                "battery-aggregates-v1 (SQL over verified classifications; not embedding clustering)");
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
    public VocSweepReceipt deviationSweep() {
        String tenant = tenantScope.currentTenantId();
        String isoWeek = isoWeekNow();
        int fired = 0;
        int notified = 0;
        for (VocSummary.Aspect a : summary().aspects()) {
            if (!a.deviating()) {
                continue;
            }
            String aspect = a.aspect();
            if (alerts.existsByTenantIdAndAspectAndIsoWeek(tenant, aspect, isoWeek)) {
                continue;
            }
            VocAlert alert = new VocAlert();
            alert.setId(UUID.randomUUID().toString());
            alert.setTenantId(tenant);
            alert.setAspect(aspect);
            alert.setIsoWeek(isoWeek);
            alert.setWeekNegatives(a.weekNegatives());
            double baseline = a.baselineWeeklyNegatives();
            alert.setBaselineAvg(BigDecimal.valueOf(baseline));
            alert.setRatio(BigDecimal.valueOf(baseline == 0 ? 99 : round2(a.weekNegatives() / baseline)));
            alert.setCreatedAt(OffsetDateTime.now());
            alerts.save(alert);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("aspect", aspect);
            event.put("isoWeek", isoWeek);
            event.put("weekNegatives", alert.getWeekNegatives());
            event.put("baselineWeeklyNegatives", baseline);
            events.publish("VocDeviationEvent", "vocDeviation", event);
            // the act half (SI-P5): the warning lands where the team lives
            notified += notifier.notify(tenant, "⚠ VoC early warning — " + aspect + ": "
                    + alert.getWeekNegatives() + " negative signals this week vs a "
                    + baseline + "/week baseline (" + isoWeek + "). Customers are telling you something.");
            fired++;
        }
        return new VocSweepReceipt(fired, notified, isoWeek);
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
