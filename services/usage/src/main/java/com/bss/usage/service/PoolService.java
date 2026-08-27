package com.bss.usage.service;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.entity.AllowancePool;
import com.bss.usage.entity.PoolMember;
import com.bss.usage.entity.UsageRecord;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.exception.ConflictException;
import com.bss.usage.exception.NotFoundException;
import com.bss.usage.repository.AllowancePoolRepository;
import com.bss.usage.repository.PoolMemberRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
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
import java.util.Optional;
import java.util.UUID;

/**
 * The household data pool: one shared bucket the owner funds, member
 * subscriptions draw down. Management is gated by the same household roles
 * gifting uses (owner = the payer; admins act for them). Consumption is a
 * per-usage-record reserve-then-commit against the locked pool row.
 */
@Service
public class PoolService {

    public static final String ACTIVE = "active";

    private final AllowancePoolRepository pools;
    private final PoolMemberRepository members;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final HouseholdGuard household;
    private final TenantClock clock;

    /** The largest single grant: quota leaves the pool in small slices, never
     * a session-sized chunk (the classic over-allocation race). */
    private static final BigDecimal GRANT_SLICE_GB = BigDecimal.ONE;

    public PoolService(AllowancePoolRepository pools, PoolMemberRepository members,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope,
            HouseholdGuard household, TenantClock clock) {
        this.pools = pools;
        this.members = members;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.household = household;
        this.clock = clock;
    }

    // ---------------- management (owner / household admin / staff) ----------------

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String tenantId = tenantScope.currentTenantId();
        String scoped = partyScope.scopedPartyId().orElse(null);
        String owner = scoped != null ? scoped
                : (dto.get("ownerPartyId") == null ? null : String.valueOf(dto.get("ownerPartyId")));
        if (owner == null) {
            throw new BadRequestException("ownerPartyId is required for unscoped callers");
        }
        if (dto.get("poolGB") == null) {
            throw new BadRequestException("poolGB is required");
        }
        BigDecimal size = new BigDecimal(String.valueOf(dto.get("poolGB")));
        if (size.signum() <= 0) {
            throw new BadRequestException("poolGB must be positive");
        }
        AllowancePool pool = new AllowancePool();
        pool.setId(UUID.randomUUID().toString());
        pool.setTenantId(tenantId);
        pool.setName(dto.get("name") == null ? "Family data pool" : String.valueOf(dto.get("name")));
        pool.setOwnerPartyId(owner);
        pool.setUsageSpecName(dto.get("usageType") == null ? null : String.valueOf(dto.get("usageType")));
        pool.setPoolValue(size);
        pool.setUnits("GB");
        pool.setConsumedValue(BigDecimal.ZERO);
        pool.setConsumedPeriod(period());
        pool.setStatus(ACTIVE);
        pool.setCreatedAt(OffsetDateTime.now());
        pools.save(pool);
        // the owner draws the pool too — a member row from the start
        saveMember(pool, owner, null, null);
        return poolMap(pool, true);
    }

    @Transactional
    public Map<String, Object> addMember(String poolId, Map<String, Object> dto) {
        AllowancePool pool = managedPool(poolId);
        if (dto.get("partyId") == null) {
            throw new BadRequestException("partyId is required");
        }
        String partyId = String.valueOf(dto.get("partyId"));
        // scoped callers stay inside their household; staff attach freely
        if (partyScope.scopedPartyId().isPresent()
                && !household.inHousehold(partyId, pool.getOwnerPartyId())) {
            throw new BadRequestException("no household link to that person");
        }
        if (members.findByTenantIdAndPoolIdAndPartyId(pool.getTenantId(), poolId, partyId).isPresent()) {
            throw new ConflictException("already a member of this pool");
        }
        if (!members.findByTenantIdAndPartyIdAndStatus(pool.getTenantId(), partyId, ACTIVE).isEmpty()) {
            throw new ConflictException("already drawing another pool");
        }
        saveMember(pool, partyId, decimal(dto.get("softLimitGB")), decimal(dto.get("hardLimitGB")));
        return poolMap(pool, true);
    }

    @Transactional
    public Map<String, Object> patchMember(String poolId, String partyId, Map<String, Object> dto) {
        AllowancePool pool = managedPool(poolId);
        PoolMember member = members.findByTenantIdAndPoolIdAndPartyId(pool.getTenantId(), poolId, partyId)
                .orElseThrow(() -> NotFoundException.forResource("PoolMember", partyId));
        if (dto.containsKey("softLimitGB")) {
            member.setSoftLimitValue(decimal(dto.get("softLimitGB")));
        }
        if (dto.containsKey("hardLimitGB")) {
            member.setHardLimitValue(decimal(dto.get("hardLimitGB")));
        }
        if (member.getSoftLimitValue() != null && member.getHardLimitValue() != null
                && member.getSoftLimitValue().compareTo(member.getHardLimitValue()) > 0) {
            throw new BadRequestException("softLimitGB cannot exceed hardLimitGB");
        }
        members.save(member);
        return poolMap(pool, true);
    }

    @Transactional
    public void removeMember(String poolId, String partyId) {
        AllowancePool pool = managedPool(poolId);
        if (partyId.equals(pool.getOwnerPartyId())) {
            throw new BadRequestException("the owner funds the pool — dissolve it instead");
        }
        PoolMember member = members.findByTenantIdAndPoolIdAndPartyId(pool.getTenantId(), poolId, partyId)
                .orElseThrow(() -> NotFoundException.forResource("PoolMember", partyId));
        members.delete(member);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String poolId) {
        String tenantId = tenantScope.currentTenantId();
        AllowancePool pool = pools.findByIdAndTenantId(poolId, tenantId)
                .orElseThrow(() -> NotFoundException.forResource("AllowancePool", poolId));
        String scoped = partyScope.scopedPartyId().orElse(null);
        if (scoped == null || household.managesHousehold(scoped, pool.getOwnerPartyId())) {
            return poolMap(pool, true);
        }
        // a plain member sees the pool's totals and their own draw, not the family's
        if (members.findByTenantIdAndPoolIdAndPartyId(tenantId, poolId, scoped).isEmpty()) {
            throw NotFoundException.forResource("AllowancePool", poolId);
        }
        return poolMap(pool, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        String tenantId = tenantScope.currentTenantId();
        String scoped = partyScope.scopedPartyId().orElse(null);
        if (scoped == null) {
            return pools.findByTenantId(tenantId).stream().map(p -> poolMap(p, true)).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AllowancePool pool : pools.findByTenantIdAndOwnerPartyId(tenantId, scoped)) {
            out.add(poolMap(pool, true));
        }
        for (PoolMember m : members.findByTenantIdAndPartyIdAndStatus(tenantId, scoped, ACTIVE)) {
            pools.findByIdAndTenantId(m.getPoolId(), tenantId)
                    .filter(p -> !p.getOwnerPartyId().equals(scoped))
                    .ifPresent(p -> out.add(poolMap(p, false)));
        }
        return out;
    }

    // ---------------- consumption (the reservation layer) ----------------

    /**
     * Draw the pool for one home-network GB usage record. CONCURRENCY: the
     * pool row is read FOR UPDATE, so concurrent records serialize here and
     * the pool can never over-allocate; inside the lock the draw is granted
     * in small slices (≤1 GB each, bounded by the record itself) — the
     * reserve-then-commit both happen in this one transaction, so a rollback
     * releases the reservation with it and no session ever holds an
     * unconsumed session-sized grant. Returns the covered portion.
     */
    @Transactional
    public BigDecimal consume(UsageRecord record) {
        if (record.getOwnerPartyId() == null || record.getZone() != null
                || !"GB".equalsIgnoreCase(String.valueOf(record.getUnits()))
                || record.getValue() == null || record.getValue().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        String tenantId = record.getTenantId();
        Optional<PoolMember> membership = members
                .findByTenantIdAndPartyIdAndStatus(tenantId, record.getOwnerPartyId(), ACTIVE)
                .stream().findFirst();
        if (membership.isEmpty()) {
            return BigDecimal.ZERO;
        }
        PoolMember member = membership.get();
        AllowancePool pool = pools.findWithLockByIdAndTenantId(member.getPoolId(), tenantId).orElse(null);
        if (pool == null || !ACTIVE.equals(pool.getStatus())
                || (pool.getUsageSpecName() != null
                        && !pool.getUsageSpecName().equals(record.getUsageSpecName()))) {
            return BigDecimal.ZERO;
        }
        LocalDate period = period();
        resetForPeriod(pool, period);
        resetForPeriod(member, period);

        BigDecimal beforePoolPct = pct(pool.getConsumedValue(), pool.getPoolValue());
        BigDecimal memberBefore = member.getConsumedValue();

        BigDecimal want = record.getValue();
        BigDecimal covered = BigDecimal.ZERO;
        while (want.signum() > 0) {
            BigDecimal poolLeft = pool.getPoolValue().subtract(pool.getConsumedValue());
            BigDecimal memberLeft = member.getHardLimitValue() == null ? want
                    : member.getHardLimitValue().subtract(member.getConsumedValue());
            BigDecimal grant = want.min(GRANT_SLICE_GB).min(poolLeft).min(memberLeft);
            if (grant.signum() <= 0) {
                break;      // pool dry or member at hard cap: fall back to own allowance
            }
            pool.setConsumedValue(pool.getConsumedValue().add(grant));
            member.setConsumedValue(member.getConsumedValue().add(grant));
            covered = covered.add(grant);
            want = want.subtract(grant);
        }
        if (covered.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        pools.save(pool);
        members.save(member);
        record.setPooledValue(covered);

        // threshold events on the crossing only — the lock serializes these too
        BigDecimal afterPoolPct = pct(pool.getConsumedValue(), pool.getPoolValue());
        for (int threshold : new int[] { 80, 100 }) {
            BigDecimal t = BigDecimal.valueOf(threshold);
            if (beforePoolPct.compareTo(t) < 0 && afterPoolPct.compareTo(t) >= 0) {
                events.publish("PoolThresholdEvent", "allowancePool", Map.of(
                        "id", pool.getId(), "name", pool.getName(),
                        "relatedParty", List.of(Map.of("id", pool.getOwnerPartyId(), "role", "owner")),
                        "threshold", threshold, "consumedGB", pool.getConsumedValue(),
                        "poolGB", pool.getPoolValue(), "period", period.toString()), tenantId);
            }
        }
        memberCapEvent(pool, member, memberBefore, "soft", member.getSoftLimitValue(), tenantId, period);
        memberCapEvent(pool, member, memberBefore, "hard", member.getHardLimitValue(), tenantId, period);
        return covered;
    }

    /** The TMF677 extension: the pool bucket (+ per-member buckets for household managers). */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> reportSection(String partyId) {
        String tenantId = tenantScope.currentTenantId();
        return members.findByTenantIdAndPartyIdAndStatus(tenantId, partyId, ACTIVE).stream()
                .findFirst()
                .flatMap(m -> pools.findByIdAndTenantId(m.getPoolId(), tenantId))
                .map(pool -> poolMap(pool, pool.getOwnerPartyId().equals(partyId)));
    }

    private void memberCapEvent(AllowancePool pool, PoolMember member, BigDecimal before,
            String capType, BigDecimal cap, String tenantId, LocalDate period) {
        if (cap == null || before.compareTo(cap) >= 0 || member.getConsumedValue().compareTo(cap) < 0) {
            return;
        }
        events.publish("MemberCapReachedEvent", "poolMember", Map.of(
                "poolId", pool.getId(),
                "relatedParty", List.of(
                        Map.of("id", member.getPartyId(), "role", "member"),
                        Map.of("id", pool.getOwnerPartyId(), "role", "owner")),
                "capType", capType, "capGB", cap,
                "consumedGB", member.getConsumedValue(), "period", period.toString()), tenantId);
    }

    private AllowancePool managedPool(String poolId) {
        String tenantId = tenantScope.currentTenantId();
        AllowancePool pool = pools.findByIdAndTenantId(poolId, tenantId)
                .orElseThrow(() -> NotFoundException.forResource("AllowancePool", poolId));
        partyScope.scopedPartyId().ifPresent(caller -> {
            if (!household.managesHousehold(caller, pool.getOwnerPartyId())) {
                throw NotFoundException.forResource("AllowancePool", poolId);
            }
        });
        return pool;
    }

    private void saveMember(AllowancePool pool, String partyId, BigDecimal soft, BigDecimal hard) {
        PoolMember member = new PoolMember();
        member.setId(UUID.randomUUID().toString());
        member.setTenantId(pool.getTenantId());
        member.setPoolId(pool.getId());
        member.setPartyId(partyId);
        member.setSoftLimitValue(soft);
        member.setHardLimitValue(hard);
        member.setConsumedValue(BigDecimal.ZERO);
        member.setConsumedPeriod(period());
        member.setStatus(ACTIVE);
        member.setCreatedAt(OffsetDateTime.now());
        members.save(member);
    }

    private Map<String, Object> poolMap(AllowancePool pool, boolean withMembers) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pool.getId());
        map.put("href", ApiConstants.BASE_PATH + "/allowancePool/" + pool.getId());
        map.put("name", pool.getName());
        map.put("ownerPartyId", pool.getOwnerPartyId());
        if (pool.getUsageSpecName() != null) {
            map.put("usageType", pool.getUsageSpecName());
        }
        LocalDate period = period();
        BigDecimal consumed = period.equals(pool.getConsumedPeriod())
                ? pool.getConsumedValue() : BigDecimal.ZERO;
        map.put("poolGB", pool.getPoolValue());
        map.put("consumedGB", consumed);
        map.put("remainingGB", pool.getPoolValue().subtract(consumed).max(BigDecimal.ZERO));
        map.put("units", pool.getUnits());
        map.put("status", pool.getStatus());
        map.put("@type", "AllowancePool");
        if (withMembers) {
            List<Map<String, Object>> bucket = new ArrayList<>();
            for (PoolMember m : members.findByTenantIdAndPoolId(pool.getTenantId(), pool.getId())) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("partyId", m.getPartyId());
                b.put("consumedGB", period.equals(m.getConsumedPeriod())
                        ? m.getConsumedValue() : BigDecimal.ZERO);
                if (m.getSoftLimitValue() != null) {
                    b.put("softLimitGB", m.getSoftLimitValue());
                }
                if (m.getHardLimitValue() != null) {
                    b.put("hardLimitGB", m.getHardLimitValue());
                }
                b.put("status", m.getStatus());
                bucket.add(b);
            }
            map.put("member", bucket);
        }
        return map;
    }

    private void resetForPeriod(AllowancePool pool, LocalDate period) {
        if (!period.equals(pool.getConsumedPeriod())) {
            pool.setConsumedPeriod(period);
            pool.setConsumedValue(BigDecimal.ZERO);
        }
    }

    private void resetForPeriod(PoolMember member, LocalDate period) {
        if (!period.equals(member.getConsumedPeriod())) {
            member.setConsumedPeriod(period);
            member.setConsumedValue(BigDecimal.ZERO);
        }
    }

    private LocalDate period() {
        return clock.today().withDayOfMonth(1);
    }

    private static BigDecimal pct(BigDecimal used, BigDecimal total) {
        return total.signum() <= 0 ? BigDecimal.ZERO
                : used.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
