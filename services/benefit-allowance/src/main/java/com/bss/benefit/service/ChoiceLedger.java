package com.bss.benefit.service;

import com.bss.benefit.dto.ChoiceEvents;
import com.bss.benefit.dto.ChoiceRequests;
import com.bss.benefit.dto.PoolView;
import com.bss.benefit.entity.ChoiceAllocation;
import com.bss.benefit.entity.ChoiceGrant;
import com.bss.benefit.entity.ChoicePool;
import com.bss.benefit.events.DomainEventPublisher;
import com.bss.benefit.exception.BadRequestException;
import com.bss.benefit.exception.ConflictException;
import com.bss.benefit.repository.ChoiceAllocationRepository;
import com.bss.benefit.repository.ChoiceGrantRepository;
import com.bss.benefit.repository.ChoicePoolRepository;
import com.bss.benefit.security.TenantRegistry;
import com.bss.benefit.security.TenantScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The pool ledger. Three rules carry the whole feature:
 * <ol>
 * <li>Free Choices = active grants − held − committed, and a hold that would
 * take free below zero is refused (409) with the numbers that refused it.</li>
 * <li>Every hold locks the household's pool row first, so two simultaneous
 * picks against one free Choice serialise: one wins, one is refused.</li>
 * <li>The order item is the duplicate key: the same item held twice returns
 * the first allocation, never a second one.</li>
 * </ol>
 * Choices are counted, never money.
 */
@Service
public class ChoiceLedger {

    public static final String HELD = "held";
    public static final String COMMITTED = "committed";
    public static final String RELEASED = "released";
    public static final String SWAPPED = "swapped";
    private static final Set<String> IN_USE = Set.of(HELD, COMMITTED);
    private static final String ACTIVE = "active";

    private final ChoicePoolRepository pools;
    private final ChoiceGrantRepository grants;
    private final ChoiceAllocationRepository allocations;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final DomainEventPublisher events;
    private final PartnerCosts partnerCosts;

    public ChoiceLedger(ChoicePoolRepository pools, ChoiceGrantRepository grants,
            ChoiceAllocationRepository allocations, TenantScope tenantScope, TenantRegistry registry,
            DomainEventPublisher events, PartnerCosts partnerCosts) {
        this.pools = pools;
        this.grants = grants;
        this.allocations = allocations;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.events = events;
        this.partnerCosts = partnerCosts;
    }

    /** Whether this tenant offers Choices at all, and the word its screens use for one. */
    private TenantRegistry.TenantEntry tenant() {
        return registry.byId(tenantScope.currentTenantId());
    }

    private boolean offered() {
        TenantRegistry.TenantEntry t = tenant();
        return t != null && t.isChoices();
    }

    private String label() {
        TenantRegistry.TenantEntry t = tenant();
        return t == null || t.getChoicesLabel() == null || t.getChoicesLabel().isBlank()
                ? "Choices" : t.getChoicesLabel();
    }

    @Transactional(readOnly = true)
    public PoolView pool(String ownerPartyId) {
        String tenant = tenantScope.currentTenantId();
        ChoicePool pool = pools.findByTenantIdAndOwnerPartyId(tenant, ownerPartyId).orElse(null);
        if (pool == null) {
            return new PoolView(offered(), label(), 0, 0, 0, List.of(), List.of(), 0,
                    nextQuarterStarts().toLocalDate().toString());
        }
        return view(tenant, pool);
    }

    @Transactional
    public PoolView.Allocation hold(ChoiceRequests.Hold request) {
        String tenant = tenantScope.currentTenantId();
        if (!offered()) {
            throw new BadRequestException(label() + " are not offered here");
        }
        // the lock first: everything below reads a pool no other hold can change
        ChoicePool pool = pools.lockByOwner(tenant, request.ownerPartyId()).orElse(null);
        ChoiceAllocation existing = allocations.findByTenantIdAndOrderIdAndOrderItemId(
                tenant, request.orderId(), request.orderItemId()).orElse(null);
        if (existing != null) {
            return allocationView(existing);    // the same item again: the first answer, not a second spend
        }
        // a swap may spend the Choice it frees: the order cannot complete without ending those
        int freed = pool == null || request.replaces() == null ? 0 : request.replaces().stream()
                .map(r -> allocations.findByTenantIdAndOrderIdAndOrderItemId(tenant, r.orderId(), r.orderItemId()))
                .flatMap(java.util.Optional::stream)
                .filter(a -> a.getPoolId().equals(pool.getId()) && COMMITTED.equals(a.getState()))
                .mapToInt(ChoiceAllocation::getCost).sum();
        int left = pool == null ? 0 : left(tenant, pool.getId()) + freed;
        if (pool == null || request.cost() > left) {
            throw new ConflictException(request.offeringName() + " costs " + request.cost() + " of your "
                    + label() + " and you have " + left + " left");
        }
        // then: a benefit the household already has is not picked twice (#392 — the preview
        // asks in this same sequence, so the two never disagree); a swap that ends it is not "already picked"
        java.util.Set<String> replaced = request.replaces() == null ? java.util.Set.of() : request.replaces().stream()
                .map(r -> r.orderId() + "\u0000" + r.orderItemId()).collect(java.util.stream.Collectors.toSet());
        // pool is not null here: the count above refused a household with no pool
        boolean already = allocations.findByTenantIdAndPoolIdAndStateInOrderByCreatedAtAsc(
                        tenant, pool.getId(), IN_USE).stream()
                .anyMatch(a -> a.getOfferingId() != null && a.getOfferingId().equals(request.offeringId())
                        && !replaced.contains(a.getOrderId() + "\u0000" + a.getOrderItemId()));
        if (already) {
            throw new ConflictException(request.offeringName() + " is already picked");
        }
        ChoiceAllocation a = new ChoiceAllocation();
        a.setTenantId(tenant);
        a.setId(UUID.randomUUID().toString());
        a.setPoolId(pool.getId());
        a.setOrderId(request.orderId());
        a.setOrderItemId(request.orderItemId());
        a.setOfferingId(request.offeringId());
        a.setOfferingName(request.offeringName());
        a.setCost(request.cost());
        a.setHoldMonths(request.holdMonths());
        // what the operator agreed to pay the partner, as the catalog said at the pick (#360)
        a.setPartner(request.partner());
        a.setWholesaleCost(request.wholesaleCost());
        a.setState(HELD);
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        allocations.save(a);
        events.publish("ChoiceHeldEvent", "choiceAllocation", fact(a, pool));
        return allocationView(a);
    }

    /** A goodwill Choice: one more for the household, named by its reason (#358). */
    @Transactional
    public PoolView goodwill(ChoiceRequests.Goodwill request) {
        String tenant = tenantScope.currentTenantId();
        if (!offered()) {
            throw new BadRequestException(label() + " are not offered here");
        }
        ChoicePool pool = lockOrCreate(tenant, request.ownerPartyId());
        ChoiceGrant grant = new ChoiceGrant();
        grant.setTenantId(tenant);
        grant.setId(UUID.randomUUID().toString());
        grant.setPoolId(pool.getId());
        grant.setOrderId("goodwill-" + grant.getId());
        grant.setOrderItemId("1");
        grant.setOfferingId("goodwill");
        String reason = request.reason().trim();
        grant.setOfferingName("Goodwill: " + (reason.length() > 200 ? reason.substring(0, 200) : reason));
        grant.setChoices(1);
        grant.setState(ACTIVE);
        grant.setGrantedAt(OffsetDateTime.now());
        grants.save(grant);
        events.publish("ChoicesGrantedEvent", "choiceGrant", fact(grant, 1, pool));
        return view(tenant, pool);
    }

    /** The order completed: what it held is now in use for good, and what it bought adds to the pool. */
    @Transactional
    public PoolView settle(String orderId, ChoiceRequests.Settle request) {
        String tenant = tenantScope.currentTenantId();
        ChoicePool pool = request.grants() == null || request.grants().isEmpty()
                ? pools.lockByOwner(tenant, request.ownerPartyId()).orElse(null)
                : lockOrCreate(tenant, request.ownerPartyId());
        for (ChoiceAllocation a : allocations.findByTenantIdAndOrderIdAndState(tenant, orderId, HELD)) {
            a.setState(COMMITTED);
            // the hold starts when the benefit is in use, capped by the law of the tenant's country
            int months = HoldLaw.effectiveMonths(a.getHoldMonths(), tenant() == null ? null : tenant().getCountry());
            a.setHeldUntil(months > 0 ? OffsetDateTime.now().plusMonths(months) : null);
            a.setLastUpdate(OffsetDateTime.now());
            allocations.save(a);
            events.publish("ChoicePickedEvent", "choiceAllocation", fact(a, pool));
            partnerCosts.record(a);   // active now: this month is owed to the partner
        }
        // grants were asked for only when there is a pool to put them in (lockOrCreate above)
        if (pool != null && request.grants() != null) {
            for (ChoiceRequests.Grant g : request.grants()) {
                if (grants.findByTenantIdAndOrderIdAndOrderItemId(tenant, orderId, g.orderItemId()).isPresent()) {
                    continue;   // the completion arrived twice; the plan granted once
                }
                ChoiceGrant grant = new ChoiceGrant();
                grant.setTenantId(tenant);
                grant.setId(UUID.randomUUID().toString());
                grant.setPoolId(pool.getId());
                grant.setOrderId(orderId);
                grant.setOrderItemId(g.orderItemId());
                grant.setOfferingId(g.offeringId());
                grant.setOfferingName(g.offeringName());
                grant.setChoices(g.choices());
                grant.setState(ACTIVE);
                grant.setGrantedAt(OffsetDateTime.now());
                grants.save(grant);
                events.publish("ChoicesGrantedEvent", "choiceGrant", fact(grant, g.choices(), pool));
            }
        }
        if (pool != null && request.regrants() != null) {
            for (ChoiceRequests.Regrant r : request.regrants()) {
                grants.findByTenantIdAndOrderIdAndOrderItemId(tenant, r.orderId(), r.orderItemId())
                        .filter(g -> g.getPoolId().equals(pool.getId()) && ACTIVE.equals(g.getState()))
                        .ifPresent(g -> regrant(g, r));
            }
        }
        if (pool != null && request.endings() != null) {
            for (ChoiceRequests.Ending e : request.endings()) {
                allocations.findByTenantIdAndOrderIdAndOrderItemId(tenant, e.orderId(), e.orderItemId())
                        .filter(a -> a.getPoolId().equals(pool.getId()) && COMMITTED.equals(a.getState()))
                        .ifPresent(a -> end(a, e.swapped() ? SWAPPED : RELEASED));
            }
        }
        return pool == null ? pool(request.ownerPartyId()) : view(tenant, pool);
    }

    /** A plan changed or ended: its grant now gives what the new plan gives (0 ends it). */
    private void regrant(ChoiceGrant g, ChoiceRequests.Regrant r) {
        if (r.choices() == 0) {
            g.setState("ended");
            g.setEndedAt(OffsetDateTime.now());
        } else {
            g.setChoices(r.choices());
        }
        if (r.offeringId() != null) {
            g.setOfferingId(r.offeringId());
        }
        if (r.offeringName() != null) {
            g.setOfferingName(r.offeringName());
        }
        grants.save(g);
        events.publish("ChoicesRegrantedEvent", "choiceGrant", fact(g, r.choices(), null));
    }

    /** A benefit leaves the pool: its Choice is free again, and why it left is kept. */
    private void end(ChoiceAllocation a, String reason) {
        a.setState(RELEASED);
        a.setEndReason(reason);
        a.setEndedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        allocations.save(a);
        events.publish(SWAPPED.equals(reason) ? "ChoiceSwappedEvent" : "ChoiceReleasedEvent", "choiceAllocation",
                fact(a, null));
    }

    /** The quarter this ledger counts swaps in — calendar quarters, UTC. */
    private static OffsetDateTime quarterStarts() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        int firstMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
        return OffsetDateTime.of(now.getYear(), firstMonth, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
    }

    private static OffsetDateTime nextQuarterStarts() {
        return quarterStarts().plusMonths(3);
    }

    /** The order was cancelled or failed: what it held goes back to the pool. */
    @Transactional
    public int release(String orderId) {
        String tenant = tenantScope.currentTenantId();
        int released = 0;
        for (ChoiceAllocation a : allocations.findByTenantIdAndOrderIdAndState(tenant, orderId, HELD)) {
            end(a, RELEASED);
            released++;
        }
        return released;
    }

    private ChoicePool lockOrCreate(String tenant, String owner) {
        return pools.lockByOwner(tenant, owner).orElseGet(() -> {
            ChoicePool p = new ChoicePool();
            p.setTenantId(tenant);
            p.setId(UUID.randomUUID().toString());
            p.setOwnerPartyId(owner);
            p.setCreatedAt(OffsetDateTime.now());
            p.setLastUpdate(OffsetDateTime.now());
            try {
                return pools.saveAndFlush(p);
            } catch (DataIntegrityViolationException raced) {
                // another settle created it a moment ago: take that one, locked
                return pools.lockByOwner(tenant, owner).orElseThrow(() -> raced);
            }
        });
    }

    private int granted(String tenant, String poolId) {
        return grants.findByTenantIdAndPoolIdAndState(tenant, poolId, ACTIVE).stream()
                .mapToInt(ChoiceGrant::getChoices).sum();
    }

    private int inUse(String tenant, String poolId) {
        return allocations.findByTenantIdAndPoolIdAndStateInOrderByCreatedAtAsc(tenant, poolId, IN_USE).stream()
                .mapToInt(ChoiceAllocation::getCost).sum();
    }

    private int left(String tenant, String poolId) {
        return granted(tenant, poolId) - inUse(tenant, poolId);
    }

    private PoolView view(String tenant, ChoicePool pool) {
        List<PoolView.Source> sources = grants.findByTenantIdAndPoolIdAndState(tenant, pool.getId(), ACTIVE)
                .stream().map(g -> new PoolView.Source(g.getOfferingId(), g.getOfferingName(), g.getChoices(),
                        g.getOrderId(), g.getOrderItemId()))
                .toList();
        List<PoolView.Allocation> taken = allocations
                .findByTenantIdAndPoolIdAndStateInOrderByCreatedAtAsc(tenant, pool.getId(), IN_USE)
                .stream().map(ChoiceLedger::allocationView).toList();
        int granted = sources.stream().mapToInt(PoolView.Source::choices).sum();
        int inUse = taken.stream().mapToInt(PoolView.Allocation::cost).sum();
        int swaps = (int) allocations.countByTenantIdAndPoolIdAndEndReasonAndEndedAtGreaterThanEqual(
                tenant, pool.getId(), SWAPPED, quarterStarts());
        return new PoolView(offered(), label(), granted, inUse, granted - inUse, sources, taken, swaps,
                nextQuarterStarts().toLocalDate().toString());
    }

    /** The event's view of an allocation: the household it belongs to and where its pool stands now. */
    private ChoiceEvents.Allocation fact(ChoiceAllocation a, ChoicePool known) {
        ChoicePool pool = known != null ? known : pools.findById(a.getPoolId()).orElse(null);
        int granted = pool == null ? 0 : granted(a.getTenantId(), pool.getId());
        // mid-swap the new benefit is in use before the old one ends: never below zero
        int left = pool == null ? 0 : Math.max(0, granted - inUse(a.getTenantId(), pool.getId()));
        return new ChoiceEvents.Allocation(a.getId(), a.getOfferingId(), a.getOfferingName(), a.getCost(),
                a.getState(), a.getEndReason(), a.getOrderId(), a.getOrderItemId(),
                a.getHeldUntil() == null ? null : a.getHeldUntil().toLocalDate().toString(),
                ChoiceEvents.PartyRef.customer(pool == null ? null : pool.getOwnerPartyId()), granted, left);
    }

    private ChoiceEvents.Grant fact(ChoiceGrant g, int choices, ChoicePool known) {
        ChoicePool pool = known != null ? known : pools.findById(g.getPoolId()).orElse(null);
        int granted = pool == null ? 0 : granted(g.getTenantId(), pool.getId());
        // mid-swap the new benefit is in use before the old one ends: never below zero
        int left = pool == null ? 0 : Math.max(0, granted - inUse(g.getTenantId(), pool.getId()));
        return new ChoiceEvents.Grant(g.getOfferingId(), g.getOfferingName(), choices, g.getOrderId(),
                g.getOrderItemId(), ChoiceEvents.PartyRef.customer(pool == null ? null : pool.getOwnerPartyId()),
                granted, left);
    }

    private static PoolView.Allocation allocationView(ChoiceAllocation a) {
        return new PoolView.Allocation(a.getId(), a.getOfferingId(), a.getOfferingName(), a.getCost(),
                a.getState(), a.getOrderId(), a.getOrderItemId(),
                a.getHeldUntil() == null ? null : a.getHeldUntil().toLocalDate().toString());
    }
}
