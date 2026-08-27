package com.bss.basemigration.service;

import com.bss.basemigration.client.PartyClient;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.repository.MigrationPlanRepository;
import com.bss.basemigration.security.TenantContext;
import com.bss.basemigration.security.TenantRegistry;
import com.bss.basemigration.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule triggers, scanned daily (and on demand per plan):
 *
 * AGE THRESHOLD — a birthday scheduler over party birth dates. Strategy
 * auto-migrate queues the notice at birthday − noticeDays so the order
 * lands on the birthday itself; lapse-to-grandfather only FLAGS the party
 * on the plan — eligibility was checked at sale, the customer keeps the
 * plan until they act.
 *
 * PROMO EXPIRY — a promo whose roll-off was disclosed at sale triggers no
 * new exit right, but the courtesy heads-up is universal practice: the
 * plan carries an explicit end date, and the cohort is scheduled with
 * courtesy notices (exitRight=false) as the date approaches. (Per-party
 * promo redemptions are not cheaply scannable across the base; the
 * explicit date on the plan is the honest v1.)
 */
@Component
public class TriggerScanner {

    private static final Logger log = LoggerFactory.getLogger(TriggerScanner.class);

    public static final String STRATEGY_AUTO = "auto-migrate";
    public static final String STRATEGY_GRANDFATHER = "lapse-to-grandfather";

    private final MigrationPlanRepository plans;
    private final CandidateDiscovery discovery;
    private final PartyClient parties;
    private final TenantRegistry tenants;
    private final TickGuard tickGuard;
    private final Json json;
    private final Clock clock;

    public TriggerScanner(MigrationPlanRepository plans, CandidateDiscovery discovery,
            PartyClient parties, TenantRegistry tenants, TickGuard tickGuard, Json json, Clock clock) {
        this.plans = plans;
        this.discovery = discovery;
        this.parties = parties;
        this.tenants = tenants;
        this.tickGuard = tickGuard;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(cron = "${bss.migration.trigger-scan-cron:0 0 5 * * *}")
    public void dailyScan() {
        if (!tickGuard.claim("base-migration-triggers", Duration.ofMinutes(10))) {
            return;
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    for (MigrationPlan plan : plans.findByTenantIdAndStateIn(tenant.getId(),
                            List.of(MigrationPlan.ARMED, MigrationPlan.RUNNING))) {
                        if (!MigrationPlan.TRIGGER_BULK.equals(plan.getTriggerType())) {
                            scanPlan(plan);
                        }
                    }
                } catch (Exception e) {
                    log.warn("trigger scan skipped tenant '{}': {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("base-migration-triggers");
        }
    }

    /** One plan's scan; returns what it did (the on-demand endpoint's answer). */
    public Map<String, Object> scanPlan(MigrationPlan plan) {
        return switch (plan.getTriggerType()) {
            case MigrationPlan.TRIGGER_AGE -> scanAge(plan);
            case MigrationPlan.TRIGGER_PROMO -> scanPromoExpiry(plan);
            default -> Map.of("triggerType", plan.getTriggerType(), "scheduled", 0,
                    "note", "bulk plans discover on arm; nothing to scan");
        };
    }

    private Map<String, Object> scanAge(MigrationPlan plan) {
        Map<String, Object> rule = json.readMap(plan.getTriggerJson());
        int ageYears = rule.get("ageYears") instanceof Number n ? n.intValue() : -1;
        String strategy = rule.get("strategy") == null
                ? STRATEGY_AUTO : String.valueOf(rule.get("strategy"));
        LocalDate today = LocalDate.now(clock);
        int scheduled = 0;
        int grandfathered = 0;
        List<String> flagged = new ArrayList<>(json.readStrings(plan.getGrandfatheredJson()));
        for (Map<String, Object> individual : parties.listIndividuals()) {
            String partyId = String.valueOf(individual.get("id"));
            LocalDate birth = parseDate(individual.get("birthDate"));
            if (birth == null || ageYears < 0) {
                continue;
            }
            LocalDate thresholdBirthday = birth.plusYears(ageYears);
            // eligible once the birthday is within the notice horizon, so the
            // notice can go at birthday − noticeDays and the order land on the day
            if (thresholdBirthday.isAfter(today.plusDays(plan.getNoticeDays()))) {
                continue;
            }
            if (STRATEGY_GRANDFATHER.equals(strategy)) {
                if (!flagged.contains(partyId)) {
                    flagged.add(partyId);
                    grandfathered++;
                }
                continue;
            }
            OffsetDateTime noticeAt = thresholdBirthday.minusDays(plan.getNoticeDays())
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime now = OffsetDateTime.now(clock);
            scheduled += discovery.discoverForParty(plan, partyId,
                    noticeAt.isAfter(now) ? noticeAt : now);
        }
        if (grandfathered > 0) {
            plan.setGrandfatheredJson(json.write(flagged));
            plan.setLastUpdate(OffsetDateTime.now(clock));
            plans.save(plan);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggerType", MigrationPlan.TRIGGER_AGE);
        result.put("strategy", strategy);
        result.put("scheduled", scheduled);
        result.put("grandfathered", grandfathered);
        return result;
    }

    private Map<String, Object> scanPromoExpiry(MigrationPlan plan) {
        Map<String, Object> rule = json.readMap(plan.getTriggerJson());
        LocalDate endDate = parseDate(rule.get("endDate"));
        LocalDate today = LocalDate.now(clock);
        int scheduled = 0;
        boolean due = endDate != null && !today.isBefore(endDate.minusDays(plan.getNoticeDays()));
        if (due) {
            // courtesy notice, no exit right — the roll-off was disclosed at sale
            scheduled = discovery.discoverBulk(plan, true, OffsetDateTime.now(clock));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggerType", MigrationPlan.TRIGGER_PROMO);
        result.put("endDate", endDate == null ? null : endDate.toString());
        result.put("due", due);
        result.put("scheduled", scheduled);
        return result;
    }

    private static LocalDate parseDate(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(raw));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
