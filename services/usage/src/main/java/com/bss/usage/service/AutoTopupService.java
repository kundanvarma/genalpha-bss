package com.bss.usage.service;

import com.bss.usage.dto.AutoTopupPolicyRequest;
import com.bss.usage.dto.AutoTopupPolicyView;
import com.bss.usage.dto.UsageThresholdNotification;
import com.bss.usage.entity.AllowanceBoost;
import com.bss.usage.entity.AutoTopupPolicy;
import com.bss.usage.entity.SpendMeter;
import com.bss.usage.entity.UsageAllowance;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.repository.AllowanceBoostRepository;
import com.bss.usage.repository.AutoTopupPolicyRepository;
import com.bss.usage.repository.UsageAllowanceRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in auto top-up: the OCS's threshold breach (the same notification the
 * growth engine hears) triggers a boost purchase through the existing
 * allowance-boost machinery — idempotent per breach window per cycle, bounded
 * by per-cycle count and spend caps, and NEVER without a recorded consent.
 */
@Service
public class AutoTopupService {

    private static final Logger log = LoggerFactory.getLogger(AutoTopupService.class);

    private final AutoTopupPolicyRepository policies;
    private final AllowanceBoostRepository boosts;
    private final UsageAllowanceRepository allowances;
    private final com.bss.usage.client.CatalogClient catalog;
    private final SpendPolicyService spendPolicy;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final TenantClock clock;

    public AutoTopupService(AutoTopupPolicyRepository policies, AllowanceBoostRepository boosts,
            UsageAllowanceRepository allowances, com.bss.usage.client.CatalogClient catalog,
            SpendPolicyService spendPolicy, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, TenantClock clock) {
        this.policies = policies;
        this.boosts = boosts;
        this.allowances = allowances;
        this.catalog = catalog;
        this.spendPolicy = spendPolicy;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.clock = clock;
    }

    // ---------------- customer CRUD (PartyScope) ----------------

    @Transactional(readOnly = true)
    public AutoTopupPolicyView get(String requestedPartyId) {
        String tenantId = tenantScope.currentTenantId();
        String party = resolveParty(requestedPartyId);
        return policies.findByTenantIdAndPartyId(tenantId, party)
                .map(this::policyView)
                .orElseGet(() -> AutoTopupPolicyView.disabled(party));
    }

    /** PUT semantics: the policy is small enough to state whole. Enabling
     * REQUIRES consent:true in the same request — the consent timestamp is
     * part of the record, and there is no default-on path anywhere. */
    @Transactional
    public AutoTopupPolicyView put(String requestedPartyId, AutoTopupPolicyRequest dto) {
        String tenantId = tenantScope.currentTenantId();
        String party = resolveParty(requestedPartyId);
        AutoTopupPolicy policy = policies.findByTenantIdAndPartyId(tenantId, party)
                .orElseGet(() -> {
                    AutoTopupPolicy p = new AutoTopupPolicy();
                    p.setId(UUID.randomUUID().toString());
                    p.setTenantId(tenantId);
                    p.setPartyId(party);
                    p.setCreatedAt(OffsetDateTime.now());
                    return p;
                });
        boolean enable = dto.enable();
        if (enable) {
            if (!dto.consented() && policy.getConsentAt() == null) {
                throw new BadRequestException("auto top-up is opt-in: consent=true is required to enable");
            }
            if (dto.boostOfferingId() == null && policy.getBoostOfferingId() == null) {
                throw new BadRequestException("boostOfferingId is required");
            }
            if (policy.getConsentAt() == null) {
                policy.setConsentAt(OffsetDateTime.now());
            }
        }
        policy.setEnabled(enable);
        if (dto.boostOfferingId() != null) {
            policy.setBoostOfferingId(dto.boostOfferingId());
        }
        String trigger = dto.trigger() != null ? dto.trigger()
                : policy.getTriggerType() == null ? AutoTopupPolicy.TRIGGER_DEPLETION : policy.getTriggerType();
        if (!AutoTopupPolicy.TRIGGER_DEPLETION.equals(trigger)
                && !AutoTopupPolicy.TRIGGER_THRESHOLD.equals(trigger)) {
            throw new BadRequestException("trigger must be depletion or thresholdPct");
        }
        policy.setTriggerType(trigger);
        if (dto.triggerPct() != null) {
            policy.setTriggerPct(dto.triggerPct());
        }
        if (AutoTopupPolicy.TRIGGER_THRESHOLD.equals(trigger) && policy.getTriggerPct() == null) {
            throw new BadRequestException("triggerPct is required for the thresholdPct trigger");
        }
        if (dto.maxBoostsPerCycle() != null) {
            int max = dto.maxBoostsPerCycle();
            if (max < 1) {
                throw new BadRequestException("maxBoostsPerCycle must be at least 1");
            }
            policy.setMaxBoostsPerCycle(max);
        } else if (policy.getMaxBoostsPerCycle() == 0) {
            policy.setMaxBoostsPerCycle(1);
        }
        if (dto.hasMaxSpendPerCycle()) {
            policy.setMaxSpendPerCycle(dto.maxSpendPerCycleValue());
        }
        policy.setUpdatedAt(OffsetDateTime.now());
        return policyView(policies.save(policy));
    }

    // ---------------- the breach consumer ----------------

    /**
     * Called on every OCS usage-threshold notification, INSIDE the same
     * transaction that relays the event. Idempotent per breach window: the
     * boost's marker is (period, party, threshold) and the existing
     * (order, spec) uniqueness makes a replayed notification a no-op.
     */
    @Transactional
    public void onThresholdBreach(String tenantId, String partyId, UsageThresholdNotification notification) {
        AutoTopupPolicy policy = policies.findByTenantIdAndPartyId(tenantId, partyId).orElse(null);
        if (policy == null || !policy.isEnabled() || policy.getConsentAt() == null
                || policy.getBoostOfferingId() == null) {
            return;
        }
        BigDecimal percentUsed = notification.percentUsed();
        BigDecimal remaining = notification.remainingGB();
        boolean fires = AutoTopupPolicy.TRIGGER_DEPLETION.equals(policy.getTriggerType())
                ? (percentUsed != null && percentUsed.compareTo(BigDecimal.valueOf(100)) >= 0)
                        || (remaining != null && remaining.signum() <= 0)
                : percentUsed != null && policy.getTriggerPct() != null
                        && percentUsed.compareTo(policy.getTriggerPct()) >= 0;
        if (!fires) {
            return;
        }
        LocalDate period = clock.today().withDayOfMonth(1);
        // breach window: the OCS's windowId when it sends one (a NEW breach
        // after a refill), else the threshold value — replays of the same
        // window never buy twice
        String window = notification.windowId() != null
                ? notification.windowId()
                : notification.threshold() == null
                        ? "breach" : notification.threshold().toString();
        String marker = "autotopup-" + period + "-" + partyId + "-" + window;

        List<UsageAllowance> rules = allowances
                .findByTenantIdAndProductOfferingId(tenantId, policy.getBoostOfferingId())
                .stream().filter(UsageAllowance::isBoost).toList();
        if (rules.isEmpty()) {
            log.warn("auto top-up for {} points at offering {} with no boost allowance — skipping",
                    partyId, policy.getBoostOfferingId());
            return;
        }
        // one purchase per breach window per cycle — replayed notifications land here
        if (boosts.existsByTenantIdAndProductOrderIdAndUsageSpecName(
                tenantId, marker, rules.get(0).getUsageSpecName())) {
            return;
        }
        long purchasedThisCycle = boosts
                .findByTenantIdAndOwnerPartyIdAndPeriodStart(tenantId, partyId, period).stream()
                .filter(b -> "auto-topup".equals(b.getSource()))
                .map(AllowanceBoost::getProductOrderId)
                .distinct().count();
        BigDecimal price = catalog.priceOf(policy.getBoostOfferingId()).orElse(BigDecimal.ZERO);
        boolean countCapped = purchasedThisCycle >= policy.getMaxBoostsPerCycle();
        boolean spendCapped = policy.getMaxSpendPerCycle() != null && price.signum() > 0
                && price.multiply(BigDecimal.valueOf(purchasedThisCycle + 1))
                        .compareTo(policy.getMaxSpendPerCycle()) > 0;
        if (countCapped || spendCapped) {
            events.publish("AutoTopupCapReachedEvent", "autoTopupPolicy", Map.of(
                    "relatedParty", List.of(Map.of("id", partyId, "role", "customer")),
                    "boostOfferingId", policy.getBoostOfferingId(),
                    "capType", countCapped ? "count" : "spend",
                    "purchasedThisCycle", purchasedThisCycle,
                    "period", period.toString()), tenantId);
            return;
        }
        BigDecimal totalGb = BigDecimal.ZERO;
        for (UsageAllowance rule : rules) {
            AllowanceBoost boost = new AllowanceBoost();
            boost.setId(UUID.randomUUID().toString());
            boost.setTenantId(tenantId);
            boost.setOwnerPartyId(partyId);
            boost.setUsageSpecName(rule.getUsageSpecName());
            boost.setBoostValue(rule.getAllowanceValue());
            boost.setUnits(rule.getUnits());
            boost.setPeriodStart(period);
            boost.setProductOrderId(marker);
            boost.setSource("auto-topup");
            boost.setCreatedAt(OffsetDateTime.now());
            boosts.save(boost);
            totalGb = totalGb.add(rule.getAllowanceValue());
        }
        if (price.signum() > 0) {
            // the boost is a charge like any other: it lands on the spend cap
            spendPolicy.accrue(tenantId, partyId, "boost", price, null);
        }
        events.publish("AutoTopupPurchasedEvent", "autoTopup", Map.of(
                "relatedParty", List.of(Map.of("id", partyId, "role", "customer")),
                "boostOfferingId", policy.getBoostOfferingId(),
                "amount", totalGb, "units", rules.get(0).getUnits() == null ? "GB" : rules.get(0).getUnits(),
                "price", price, "purchaseNo", purchasedThisCycle + 1,
                "window", window, "period", period.toString()), tenantId);
        log.info("auto top-up: {} bought {} ({} this cycle) on breach window {}",
                partyId, policy.getBoostOfferingId(), purchasedThisCycle + 1, window);
    }

    private String resolveParty(String requestedPartyId) {
        String scoped = partyScope.scopedPartyId().orElse(null);
        if (scoped != null) {
            if (requestedPartyId != null && !requestedPartyId.equals(scoped)) {
                throw new BadRequestException("auto top-up is the subscriber's own policy");
            }
            return scoped;
        }
        if (requestedPartyId == null) {
            throw new BadRequestException("partyId is required for unscoped callers");
        }
        return requestedPartyId;
    }

    private AutoTopupPolicyView policyView(AutoTopupPolicy p) {
        return new AutoTopupPolicyView(p.getPartyId(), p.isEnabled(), p.getBoostOfferingId(), p.getTriggerType(),
                p.getTriggerPct(), p.getMaxBoostsPerCycle(), p.getMaxSpendPerCycle(),
                p.getConsentAt() == null ? null : p.getConsentAt().toString(), "AutoTopupPolicy");
    }
}
