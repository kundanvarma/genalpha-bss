package com.bss.benefit.service;

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
    private static final Set<String> IN_USE = Set.of(HELD, COMMITTED);
    private static final String ACTIVE = "active";

    private final ChoicePoolRepository pools;
    private final ChoiceGrantRepository grants;
    private final ChoiceAllocationRepository allocations;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final DomainEventPublisher events;

    public ChoiceLedger(ChoicePoolRepository pools, ChoiceGrantRepository grants,
            ChoiceAllocationRepository allocations, TenantScope tenantScope, TenantRegistry registry,
            DomainEventPublisher events) {
        this.pools = pools;
        this.grants = grants;
        this.allocations = allocations;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.events = events;
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
            return new PoolView(offered(), label(), 0, 0, 0, List.of(), List.of());
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
        int left = pool == null ? 0 : left(tenant, pool.getId());
        if (pool == null || request.cost() > left) {
            throw new ConflictException(request.offeringName() + " costs " + request.cost() + " of your "
                    + label() + " and you have " + left + " left");
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
        a.setState(HELD);
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        allocations.save(a);
        PoolView.Allocation view = allocationView(a);
        events.publish("ChoiceHeldEvent", "choiceAllocation", view);
        return view;
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
            a.setLastUpdate(OffsetDateTime.now());
            allocations.save(a);
            events.publish("ChoicePickedEvent", "choiceAllocation", allocationView(a));
        }
        if (request.grants() != null) {
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
                events.publish("ChoicesGrantedEvent", "choiceGrant",
                        new PoolView.Source(g.offeringId(), g.offeringName(), g.choices()));
            }
        }
        return pool == null ? pool(request.ownerPartyId()) : view(tenant, pool);
    }

    /** The order was cancelled or failed: what it held goes back to the pool. */
    @Transactional
    public int release(String orderId) {
        String tenant = tenantScope.currentTenantId();
        int released = 0;
        for (ChoiceAllocation a : allocations.findByTenantIdAndOrderIdAndState(tenant, orderId, HELD)) {
            a.setState(RELEASED);
            a.setLastUpdate(OffsetDateTime.now());
            allocations.save(a);
            events.publish("ChoiceReleasedEvent", "choiceAllocation", allocationView(a));
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
                .stream().map(g -> new PoolView.Source(g.getOfferingId(), g.getOfferingName(), g.getChoices()))
                .toList();
        List<PoolView.Allocation> taken = allocations
                .findByTenantIdAndPoolIdAndStateInOrderByCreatedAtAsc(tenant, pool.getId(), IN_USE)
                .stream().map(ChoiceLedger::allocationView).toList();
        int granted = sources.stream().mapToInt(PoolView.Source::choices).sum();
        int inUse = taken.stream().mapToInt(PoolView.Allocation::cost).sum();
        return new PoolView(offered(), label(), granted, inUse, granted - inUse, sources, taken);
    }

    private static PoolView.Allocation allocationView(ChoiceAllocation a) {
        return new PoolView.Allocation(a.getId(), a.getOfferingId(), a.getOfferingName(), a.getCost(),
                a.getState(), a.getOrderId());
    }
}
