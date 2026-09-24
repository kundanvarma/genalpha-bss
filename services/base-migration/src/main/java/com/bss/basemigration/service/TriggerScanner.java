package com.bss.basemigration.service;

import com.bss.basemigration.client.PartyClient;
import com.bss.basemigration.dto.MigrationPlanRequest;
import com.bss.basemigration.dto.TriggerScanResult;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.repository.MigrationPlanRepository;
import com.bss.basemigration.security.TenantContext;
import com.bss.basemigration.security.TenantRegistry;
import com.bss.basemigration.tick.TickGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import static com.bss.basemigration.api.Wire.idOf;

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
    public TriggerScanResult scanPlan(MigrationPlan plan) {
        return switch (plan.getTriggerType()) {
            case MigrationPlan.TRIGGER_AGE -> scanAge(plan);
            case MigrationPlan.TRIGGER_PROMO -> scanPromoExpiry(plan);
            default -> TriggerScanResult.Bulk.of(plan.getTriggerType());
        };
    }

    private TriggerScanResult scanAge(MigrationPlan plan) {
        ObjectNode rule = json.readObject(plan.getTriggerJson());
        JsonNode years = rule.get("ageYears");
        int ageYears = years != null && years.isNumber() ? years.intValue() : -1;
        String strategy = !rule.hasNonNull("strategy")
                ? STRATEGY_AUTO : MigrationPlanRequest.text(rule.get("strategy"));
        LocalDate today = LocalDate.now(clock);
        int scheduled = 0;
        int grandfathered = 0;
        List<String> flagged = new ArrayList<>(json.readStrings(plan.getGrandfatheredJson()));
        for (Map<String, Object> individual : parties.listIndividuals()) {
            String partyId = idOf(individual);
            LocalDate birth = parseDate(String.valueOf(individual.get("birthDate")));
            if (partyId == null || birth == null || ageYears < 0) {
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
        return new TriggerScanResult.Age(MigrationPlan.TRIGGER_AGE, strategy, scheduled, grandfathered);
    }

    private TriggerScanResult scanPromoExpiry(MigrationPlan plan) {
        JsonNode raw = json.readObject(plan.getTriggerJson()).get("endDate");
        LocalDate endDate = raw == null || raw.isNull() ? null : parseDate(MigrationPlanRequest.text(raw));
        LocalDate today = LocalDate.now(clock);
        int scheduled = 0;
        boolean due = endDate != null && !today.isBefore(endDate.minusDays(plan.getNoticeDays()));
        if (due) {
            // courtesy notice, no exit right — the roll-off was disclosed at sale
            scheduled = discovery.discoverBulk(plan, true, OffsetDateTime.now(clock));
        }
        return new TriggerScanResult.Promo(MigrationPlan.TRIGGER_PROMO,
                endDate == null ? null : endDate.toString(), due, scheduled);
    }

    /** {@code String.valueOf} first, as the map path did — a missing birth date reads as "null" and fails to parse. */
    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
