package com.bss.intelligence.churn;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.events.DomainEventPublisher;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deliberately NOT an LLM: churn and next-best-offer signals come from
 * structured facts a marketer can verify — a commitment ending, an
 * allowance nearly spent. Each detected risk becomes ONE
 * ChurnRiskDetectedEvent on the intelligence topic; the campaign engine
 * treats it like any other business event, so "commitment ending → offer
 * a loyalty deal" is just a campaign with a trigger, not new machinery.
 * The reason travels as the resource state, so campaigns can filter on it.
 */
@Service
public class ChurnScorer {

    public static final String COMMITMENT_ENDING = "commitment-ending";
    public static final String ALLOWANCE_PRESSURE = "allowance-pressure";
    public static final String SUPPORT_FRICTION = "support-friction";
    public static final String SERVICE_DEGRADED = "service-degraded";
    public static final String CHURN_PREDICTED = "churn-predicted";

    private static final Logger log = LoggerFactory.getLogger(ChurnScorer.class);

    private final BssApiClient bss;
    private final ChurnAlertRepository alerts;
    private final ChurnFeatureSnapshotRepository snapshots;
    private final ChurnModelService modelService;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final TransactionTemplate transaction;
    private final int agreementDays;
    private final double usageThreshold;
    private final int ticketThreshold;
    private final double predictThreshold;

    public ChurnScorer(BssApiClient bss, ChurnAlertRepository alerts,
            ChurnFeatureSnapshotRepository snapshots, ChurnModelService modelService,
            DomainEventPublisher events, TenantRegistry tenants, TransactionTemplate transaction,
            @Value("${bss.intelligence.churn.agreement-days:30}") int agreementDays,
            @Value("${bss.intelligence.churn.usage-threshold:0.9}") double usageThreshold,
            @Value("${bss.intelligence.churn.ticket-threshold:2}") int ticketThreshold,
            @Value("${bss.intelligence.churn.predict-threshold:0.7}") double predictThreshold) {
        this.bss = bss;
        this.alerts = alerts;
        this.snapshots = snapshots;
        this.modelService = modelService;
        this.events = events;
        this.tenants = tenants;
        this.transaction = transaction;
        this.agreementDays = agreementDays;
        this.usageThreshold = usageThreshold;
        this.ticketThreshold = ticketThreshold;
        this.predictThreshold = predictThreshold;
    }

    @Scheduled(initialDelayString = "${bss.intelligence.churn.initial-delay-ms:60000}",
            fixedDelayString = "${bss.intelligence.churn.interval-ms:300000}")
    public void scheduledSweep() {
        try {
            sweepAllTenants();
        } catch (Exception e) {
            log.warn("scheduled churn sweep failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> sweepAllTenants() {
        int total = 0;
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                total += sweepCurrentTenant();
            } catch (Exception e) {
                log.warn("churn sweep skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
        return Map.of("alerts", total);
    }

    public int sweepCurrentTenant() {
        int emitted = 0;
        boolean outageOngoing = !bss.openServiceProblems().isEmpty();
        var model = modelService.modelFor(TenantContext.current());
        // One feature computation per customer feeds three consumers: the
        // transparent rules, the daily training snapshot, and (once trained)
        // the per-tenant model.
        Map<String, OffsetDateTime> commitmentEnds = new java.util.HashMap<>();
        java.util.Set<String> customers = new java.util.LinkedHashSet<>();
        for (Map<String, Object> agreement : bss.activeAgreements()) {
            String party = customerOf(agreement.get("engagedParty"));
            if (party == null) {
                continue;
            }
            customers.add(party);
            OffsetDateTime end = periodEndOf(agreement);
            if (end != null && (!commitmentEnds.containsKey(party)
                    || end.isBefore(commitmentEnds.get(party)))) {
                commitmentEnds.put(party, end);
            }
        }
        OffsetDateTime horizon = OffsetDateTime.now().plusDays(agreementDays);
        for (String party : customers) {
            double maxRatio = 0;
            for (Map<String, Object> meter : bss.usageMeters(party)) {
                Double ratio = usageRatio(meter);
                if (ratio != null) {
                    maxRatio = Math.max(maxRatio, ratio);
                }
            }
            int recentTickets = 0;
            boolean openTicket = false;
            for (Map<String, Object> ticket : bss.ticketsOf(party)) {
                String status = String.valueOf(ticket.get("status"));
                if (!"closed".equals(status) && !"resolved".equals(status)) {
                    openTicket = true;
                }
                OffsetDateTime created = parseTime(ticket.get("creationDate"));
                if (created != null && created.isAfter(OffsetDateTime.now().minusDays(30))) {
                    recentTickets++;
                }
            }
            OffsetDateTime end = commitmentEnds.get(party);
            double daysToEnd = end == null ? 9999
                    : Math.max(0, java.time.Duration.between(OffsetDateTime.now(), end).toDays());
            double[] features = {daysToEnd, maxRatio, recentTickets,
                    openTicket && outageOngoing ? 1 : 0};
            snapshot(party, features);

            // The transparent rules — each reason is a fact an agent can check.
            if (end != null && end.isBefore(horizon) && end.isAfter(OffsetDateTime.now())) {
                emitted += alert(party, COMMITMENT_ENDING, scoreForDaysLeft(end));
            }
            if (maxRatio >= usageThreshold) {
                emitted += alert(party, ALLOWANCE_PRESSURE,
                        BigDecimal.valueOf(Math.min(1.0, maxRatio)).setScale(3, RoundingMode.HALF_UP));
            }
            if (recentTickets >= ticketThreshold) {
                emitted += alert(party, SUPPORT_FRICTION,
                        BigDecimal.valueOf(Math.min(1.0, recentTickets / 5.0))
                                .setScale(3, RoundingMode.HALF_UP));
            }
            if (openTicket && outageOngoing) {
                emitted += alert(party, SERVICE_DEGRADED, BigDecimal.valueOf(0.800));
            }
            // The learned layer, once this tenant has trained one.
            if (model.isPresent()) {
                double p = model.get().predict(features);
                if (p >= predictThreshold) {
                    emitted += alert(party, CHURN_PREDICTED,
                            BigDecimal.valueOf(p).setScale(3, RoundingMode.HALF_UP));
                }
            }
        }
        return emitted;
    }

    /** Today's feature vector, once per customer per day — the training corpus. */
    private void snapshot(String party, double[] features) {
        String tenant = TenantContext.current();
        java.time.LocalDate today = java.time.LocalDate.now();
        if (snapshots.existsByTenantIdAndPartyIdAndSnapshotDate(tenant, party, today)) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            ChurnFeatureSnapshot snap = new ChurnFeatureSnapshot();
            snap.setId(UUID.randomUUID().toString());
            snap.setTenantId(tenant);
            snap.setPartyId(party);
            snap.setSnapshotDate(today);
            snap.setDaysToCommitmentEnd(BigDecimal.valueOf(features[0]).setScale(1, RoundingMode.HALF_UP));
            snap.setMaxUsageRatio(BigDecimal.valueOf(features[1]).setScale(3, RoundingMode.HALF_UP));
            snap.setTicketsLast30d(BigDecimal.valueOf(features[2]));
            snap.setOpenTicketDuringOutage(BigDecimal.valueOf(features[3]));
            snap.setTakenAt(OffsetDateTime.now());
            try {
                snapshots.save(snap);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // concurrent sweep already wrote today's row — fine
            }
        });
    }

    private static OffsetDateTime parseTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /** Alert row + outbox event commit together — the outbox contract. */
    private int alert(String party, String reason, BigDecimal score) {
        String tenant = TenantContext.current();
        if (tenant == null || alerts.existsByTenantIdAndPartyIdAndReason(tenant, party, reason)) {
            return 0;
        }
        return transaction.execute(status -> {
            record(tenant, party, reason, score);
            return 1;
        });
    }

    private void record(String tenant, String party, String reason, BigDecimal score) {
        ChurnAlert alert = new ChurnAlert();
        alert.setId(UUID.randomUUID().toString());
        alert.setTenantId(tenant);
        alert.setPartyId(party);
        alert.setReason(reason);
        alert.setScore(score);
        alert.setDetectedAt(OffsetDateTime.now());
        alerts.save(alert);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", alert.getId());
        resource.put("state", reason); // campaigns filter triggerState on this
        resource.put("score", score);
        resource.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
        events.publish("ChurnRiskDetectedEvent", "churnAlert", resource, tenant);
        log.info("churn alert: {} for party {} (score {})", reason, party, score);
    }

    private static String customerOf(Object engagedParty) {
        if (engagedParty instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("id") != null
                        && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }

    private static OffsetDateTime periodEndOf(Map<String, Object> agreement) {
        if (agreement.get("agreementPeriod") instanceof Map<?, ?> period
                && period.get("endDateTime") != null) {
            try {
                return OffsetDateTime.parse(String.valueOf(period.get("endDateTime")));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal scoreForDaysLeft(OffsetDateTime end) {
        double daysLeft = Math.max(0, java.time.Duration.between(OffsetDateTime.now(), end).toDays());
        return BigDecimal.valueOf(1.0 - (daysLeft / (agreementDays * 2.0)))
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static Double usageRatio(Map<String, Object> meter) {
        Object used = meter.get("usedValue");
        Object allowed = meter.get("allowedValue");
        if (used instanceof Number u && allowed instanceof Number a && a.doubleValue() > 0) {
            return u.doubleValue() / a.doubleValue();
        }
        return null;
    }
}
