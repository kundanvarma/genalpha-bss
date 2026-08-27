package com.bss.usage.service;

import com.bss.usage.entity.SpendMeter;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.repository.SpendMeterRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One monetary-meter primitive, three legal faces:
 *  - 'spend'   subscription spend cap — off by default, customer-settable;
 *  - 'content' content-services (carrier billing) cap + barring — barring is
 *              FREE and always available, the cap's lowest selectable value is
 *              a statutory floor, minors are barred by default;
 *  - 'roaming' the default financial limit — warn at 80 %, hard cut-off at
 *              100 %, service back only on an explicit (audited) continue.
 * Statutory numbers live in a country pack (Norway defaults) that policy
 * writes cannot undercut. Charges arrive through the accrue seam: the rating
 * pass, the /spendMeter/charge endpoint, and the OCS spendThreshold webhook.
 */
@Service
public class SpendPolicyService {

    private final SpendMeterRepository meters;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final HouseholdGuard household;
    private final TenantClock clock;

    // ---- country pack (Norway defaults; overridable per deployment, never below statute) ----
    private final String packCountry;
    private final BigDecimal contentCapFloor;
    private final String contentCurrency;
    private final BigDecimal roamingDefaultLimit;
    private final String roamingCurrency;

    public SpendPolicyService(SpendMeterRepository meters, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, HouseholdGuard household,
            TenantClock clock,
            @Value("${bss.usage.policy.country:NO}") String packCountry,
            @Value("${bss.usage.policy.content-cap-floor:250}") BigDecimal contentCapFloor,
            @Value("${bss.usage.policy.content-cap-currency:NOK}") String contentCurrency,
            @Value("${bss.usage.policy.roaming-default-limit:50}") BigDecimal roamingDefaultLimit,
            @Value("${bss.usage.policy.roaming-default-currency:EUR}") String roamingCurrency) {
        this.meters = meters;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.household = household;
        this.clock = clock;
        this.packCountry = packCountry;
        this.contentCapFloor = contentCapFloor;
        this.contentCurrency = contentCurrency;
        this.roamingDefaultLimit = roamingDefaultLimit;
        this.roamingCurrency = roamingCurrency;
    }

    // ---------------- the policy face (customer / guardian / staff) ----------------

    /** The party this request may manage: self, a guardian's child, or anyone for staff. */
    public String resolveParty(String requestedPartyId) {
        String scoped = partyScope.scopedPartyId().orElse(null);
        if (scoped == null) {
            if (requestedPartyId == null) {
                throw new BadRequestException("partyId is required for unscoped callers");
            }
            return requestedPartyId;
        }
        if (requestedPartyId == null || requestedPartyId.equals(scoped)) {
            return scoped;
        }
        if (!household.guardianOf(scoped, requestedPartyId)) {
            throw new BadRequestException("no guardian link to that person");
        }
        return requestedPartyId;
    }

    @Transactional
    public List<Map<String, Object>> policyOf(String partyId) {
        String tenantId = tenantScope.currentTenantId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String type : List.of(SpendMeter.SPEND, SpendMeter.CONTENT, SpendMeter.ROAMING)) {
            out.add(meterMap(ensureMeter(tenantId, partyId, type)));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> patch(String partyId, String meterType, Map<String, Object> dto) {
        String tenantId = tenantScope.currentTenantId();
        if (!Set.of(SpendMeter.SPEND, SpendMeter.CONTENT, SpendMeter.ROAMING).contains(meterType)) {
            throw new BadRequestException("meterType must be spend, content or roaming");
        }
        SpendMeter meter = ensureMeter(tenantId, partyId, meterType);
        if (dto.containsKey("limit")) {
            BigDecimal limit = dto.get("limit") == null ? null
                    : new BigDecimal(String.valueOf(dto.get("limit")));
            if (limit != null && limit.signum() <= 0) {
                throw new BadRequestException("limit must be positive");
            }
            // the statutory floor (country pack): the lowest selectable
            // content cap — a write below it is refused, not clamped
            if (SpendMeter.CONTENT.equals(meterType) && limit != null
                    && limit.compareTo(contentCapFloor) < 0) {
                throw new BadRequestException("the lowest selectable content-services limit is "
                        + contentCapFloor.stripTrailingZeros().toPlainString() + " " + contentCurrency
                        + " (" + packCountry + " statutory floor)");
            }
            meter.setLimitValue(limit);
        }
        if (dto.containsKey("currency") && dto.get("currency") != null) {
            meter.setCurrency(String.valueOf(dto.get("currency")));
        }
        if (dto.containsKey("enabled")) {
            meter.setEnabled(Boolean.parseBoolean(String.valueOf(dto.get("enabled"))));
        }
        if (dto.containsKey("notifyAtPct")) {
            meter.setNotifyAtPct(new BigDecimal(String.valueOf(dto.get("notifyAtPct"))));
        }
        if (dto.containsKey("blockOnBreach")) {
            meter.setBlockOnBreach(Boolean.parseBoolean(String.valueOf(dto.get("blockOnBreach"))));
        }
        if (dto.containsKey("barred")) {
            if (!SpendMeter.CONTENT.equals(meterType)) {
                throw new BadRequestException("barring belongs to the content-services face");
            }
            boolean barred = Boolean.parseBoolean(String.valueOf(dto.get("barred")));
            // a minor's barring is mandatory: only a guardian or staff lifts it
            if (!barred && partyScope.scopedPartyId().map(partyId::equals).orElse(false)
                    && household.isMinor(partyId)) {
                throw new BadRequestException(
                        "content-services barring for a minor is lifted by a guardian, not the minor");
            }
            meter.setBarred(barred);
        }
        // a raised limit or re-enable clears a stale block for the new headroom
        reEvaluate(meter);
        meter.setUpdatedAt(OffsetDateTime.now());
        return meterMap(meters.save(meter));
    }

    /**
     * The audited roaming escape hatch (EU 2022/612 art. 11: service past the
     * limit only on the customer's explicit request). Restores service for the
     * rest of the cycle and says so on the event bus.
     */
    @Transactional
    public Map<String, Object> roamingContinue(String partyId) {
        String tenantId = tenantScope.currentTenantId();
        SpendMeter meter = ensureMeter(tenantId, partyId, SpendMeter.ROAMING);
        rollPeriod(meter);
        meter.setContinueElected(true);
        meter.setBlocked(false);
        meter.setUpdatedAt(OffsetDateTime.now());
        meters.save(meter);
        events.publish("RoamingContinueElectedEvent", "spendMeter", Map.of(
                "relatedParty", List.of(Map.of("id", partyId, "role", "customer")),
                "meterType", SpendMeter.ROAMING,
                "accrued", money(meter.getAccruedValue(), meter.getCurrency()),
                "limit", money(meter.getLimitValue(), meter.getCurrency()),
                "period", period().toString()), tenantId);
        return meterMap(meter);
    }

    // ---------------- the accrue seam ----------------

    /**
     * One rated charge lands on the meters its class touches: every class
     * accrues the 'spend' face; 'content' and 'roaming' also hit their own.
     * Returns {accepted, meter:[...]} — accepted=false tells the charging
     * edge (carrier-billing gateway / OCS) to refuse the charge.
     */
    @Transactional
    public Map<String, Object> accrue(String tenantId, String partyId, String chargeClass,
            BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("amount {value, unit} must be positive");
        }
        boolean accepted = true;
        List<Map<String, Object>> touched = new ArrayList<>();
        String primary = switch (chargeClass == null ? "usage" : chargeClass) {
            case "content" -> SpendMeter.CONTENT;
            case "roaming" -> SpendMeter.ROAMING;
            default -> null;    // usage / boost: the spend face only
        };
        if (primary != null) {
            SpendMeter meter = ensureMeter(tenantId, partyId, primary);
            rollPeriod(meter);
            if (refuses(meter)) {
                accepted = false;
                if (SpendMeter.CONTENT.equals(primary)) {
                    events.publish("ContentBarringBlockedEvent", "spendMeter", Map.of(
                            "relatedParty", List.of(Map.of("id", partyId, "role", "customer")),
                            "amount", money(amount, currency),
                            "reason", meter.isBarred() ? "barred" : "limit"), tenantId);
                }
                touched.add(meterMap(meter));
            } else {
                touched.add(accrueInto(meter, amount, tenantId));
            }
        }
        // the subscription cap is the outer wall: a refused charge never accrues
        SpendMeter spend = ensureMeter(tenantId, partyId, SpendMeter.SPEND);
        rollPeriod(spend);
        if (accepted && refuses(spend)) {
            accepted = false;
            touched.add(meterMap(spend));
        } else if (accepted) {
            touched.add(accrueInto(spend, amount, tenantId));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("meter", touched);
        return out;
    }

    private boolean refuses(SpendMeter meter) {
        if (SpendMeter.CONTENT.equals(meter.getMeterType()) && meter.isBarred()) {
            return true;
        }
        if (SpendMeter.ROAMING.equals(meter.getMeterType())) {
            return meter.isBlocked() && !meter.isContinueElected();
        }
        return meter.isEnabled() && meter.isBlocked();
    }

    private Map<String, Object> accrueInto(SpendMeter meter, BigDecimal amount, String tenantId) {
        BigDecimal before = meter.getAccruedValue();
        meter.setAccruedValue(before.add(amount));
        meter.setUpdatedAt(OffsetDateTime.now());
        boolean armed = meter.isEnabled() && meter.getLimitValue() != null;
        if (armed) {
            BigDecimal beforePct = pct(before, meter.getLimitValue());
            BigDecimal afterPct = pct(meter.getAccruedValue(), meter.getLimitValue());
            BigDecimal warnAt = meter.getNotifyAtPct() == null
                    ? BigDecimal.valueOf(80) : meter.getNotifyAtPct();
            if (beforePct.compareTo(warnAt) < 0 && afterPct.compareTo(warnAt) >= 0
                    && meter.getWarnedPct() == null) {
                meter.setWarnedPct(afterPct);
                publishThreshold(meter, warnAt, false, tenantId);
            }
            if (!meter.isBreached() && afterPct.compareTo(BigDecimal.valueOf(100)) >= 0) {
                meter.setBreached(true);
                if (meter.isBlockOnBreach()
                        && !(SpendMeter.ROAMING.equals(meter.getMeterType()) && meter.isContinueElected())) {
                    meter.setBlocked(true);
                }
                publishThreshold(meter, BigDecimal.valueOf(100), true, tenantId);
            }
        }
        return meterMap(meters.save(meter));
    }

    private void publishThreshold(SpendMeter meter, BigDecimal threshold, boolean breach, String tenantId) {
        String eventType = SpendMeter.ROAMING.equals(meter.getMeterType())
                ? (breach ? "RoamingLimitCutOffEvent" : "RoamingLimitWarningEvent")
                : (breach ? "SpendCapBreachEvent" : "SpendCapWarningEvent");
        events.publish(eventType, "spendMeter", Map.of(
                "relatedParty", List.of(Map.of("id", meter.getPartyId(), "role", "customer")),
                "meterType", meter.getMeterType(),
                "threshold", threshold,
                "accrued", money(meter.getAccruedValue(), meter.getCurrency()),
                "limit", money(meter.getLimitValue(), meter.getCurrency()),
                "blocked", meter.isBlocked(),
                "period", period().toString()), tenantId);
    }

    // ---------------- plumbing ----------------

    /** Lazy defaults per face. The roaming limit exists WITHOUT the customer
     * ever asking — that is the point of a default financial limit. */
    private SpendMeter ensureMeter(String tenantId, String partyId, String meterType) {
        return meters.findByTenantIdAndPartyIdAndMeterType(tenantId, partyId, meterType)
                .orElseGet(() -> {
                    SpendMeter meter = new SpendMeter();
                    meter.setId(UUID.randomUUID().toString());
                    meter.setTenantId(tenantId);
                    meter.setPartyId(partyId);
                    meter.setMeterType(meterType);
                    meter.setNotifyAtPct(BigDecimal.valueOf(80));
                    meter.setBlockOnBreach(true);
                    meter.setAccruedValue(BigDecimal.ZERO);
                    meter.setAccrualPeriod(period());
                    switch (meterType) {
                        case SpendMeter.ROAMING -> {
                            meter.setEnabled(true);
                            meter.setLimitValue(roamingDefaultLimit);
                            meter.setCurrency(roamingCurrency);
                        }
                        case SpendMeter.CONTENT -> {
                            meter.setEnabled(true);
                            meter.setCurrency(contentCurrency);
                            // mandatory default for minors, same derivation as the guardian reads
                            meter.setBarred(household.isMinor(partyId));
                        }
                        default -> meter.setEnabled(false);     // spend cap: never default-on
                    }
                    meter.setCreatedAt(OffsetDateTime.now());
                    return meters.save(meter);
                });
    }

    private void rollPeriod(SpendMeter meter) {
        LocalDate period = period();
        if (!period.equals(meter.getAccrualPeriod())) {
            meter.setAccrualPeriod(period);
            meter.setAccruedValue(BigDecimal.ZERO);
            meter.setWarnedPct(null);
            meter.setBreached(false);
            meter.setBlocked(false);
            meter.setContinueElected(false);
        }
    }

    private void reEvaluate(SpendMeter meter) {
        rollPeriod(meter);
        boolean over = meter.isEnabled() && meter.getLimitValue() != null
                && meter.getAccruedValue().compareTo(meter.getLimitValue()) >= 0;
        if (!over) {
            meter.setBreached(false);
            meter.setBlocked(false);
            meter.setWarnedPct(null);
        }
    }

    private Map<String, Object> meterMap(SpendMeter meter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("partyId", meter.getPartyId());
        map.put("meterType", meter.getMeterType());
        map.put("enabled", meter.isEnabled());
        if (SpendMeter.CONTENT.equals(meter.getMeterType())) {
            map.put("barred", meter.isBarred());
            map.put("lowestSelectableLimit", money(contentCapFloor, contentCurrency));
        }
        if (meter.getLimitValue() != null) {
            map.put("limit", money(meter.getLimitValue(), meter.getCurrency()));
        }
        map.put("notifyAtPct", meter.getNotifyAtPct());
        map.put("blockOnBreach", meter.isBlockOnBreach());
        LocalDate period = period();
        BigDecimal accrued = period.equals(meter.getAccrualPeriod())
                ? meter.getAccruedValue() : BigDecimal.ZERO;
        map.put("accrued", money(accrued, meter.getCurrency()));
        map.put("blocked", period.equals(meter.getAccrualPeriod()) && meter.isBlocked());
        if (SpendMeter.ROAMING.equals(meter.getMeterType())) {
            map.put("continueElected", period.equals(meter.getAccrualPeriod()) && meter.isContinueElected());
        }
        map.put("period", period.toString());
        map.put("@type", "SpendMeter");
        return map;
    }

    private LocalDate period() {
        return clock.today().withDayOfMonth(1);
    }

    private static BigDecimal pct(BigDecimal used, BigDecimal limit) {
        return limit == null || limit.signum() <= 0 ? BigDecimal.ZERO
                : used.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> money(BigDecimal value, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("unit", unit);
        return m;
    }
}
