package com.bss.basemigration.service;

import com.bss.basemigration.api.ApiConstants;
import com.bss.basemigration.api.PagedResult;
import com.bss.basemigration.client.OrderingClient;
import com.bss.basemigration.client.SimulationClient;
import com.bss.basemigration.dto.AttachSimulationRequest;
import com.bss.basemigration.dto.MigrationCustomerDetail;
import com.bss.basemigration.dto.MigrationCustomerView;
import com.bss.basemigration.dto.MigrationEvents;
import com.bss.basemigration.dto.MigrationPlanRequest;
import com.bss.basemigration.dto.MigrationPlanView;
import com.bss.basemigration.dto.MigrationProgress;
import com.bss.basemigration.dto.TriggerScanResult;
import com.bss.basemigration.entity.MigrationCustomer;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.events.DomainEventPublisher;
import com.bss.basemigration.exception.BadRequestException;
import com.bss.basemigration.exception.ConflictException;
import com.bss.basemigration.exception.NotFoundException;
import com.bss.basemigration.repository.MigrationCustomerRepository;
import com.bss.basemigration.repository.MigrationPlanRepository;
import com.bss.basemigration.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The migration desk: plan CRUD, the rehearsal gate (no simulation receipt,
 * no arming), the wave switches, and the per-customer doors — the exercised
 * exit the law requires, and the rollback that industry history requires.
 */
@Service
public class MigrationPlanService {

    private static final String RESOURCE = "migrationPlan";
    // these four are PRINTED in refusals; a Set.of re-orders itself on every JVM
    // start, so each is pinned to the order the message already has
    private static final Set<String> DELTA_CLASSES = new LinkedHashSet<>(
            List.of("beneficial", "neutral", "detrimental"));
    private static final Set<String> IN_BINDING = new LinkedHashSet<>(
            List.of(CandidateDiscovery.IN_BINDING_FREE_EXIT, CandidateDiscovery.IN_BINDING_EXCLUDE,
                    CandidateDiscovery.IN_BINDING_DEFER));
    private static final Set<String> TRIGGERS = new LinkedHashSet<>(
            List.of(MigrationPlan.TRIGGER_PROMO, MigrationPlan.TRIGGER_AGE, MigrationPlan.TRIGGER_BULK));
    private static final Set<String> AGE_STRATEGIES = new LinkedHashSet<>(
            List.of(TriggerScanner.STRATEGY_AUTO, TriggerScanner.STRATEGY_GRANDFATHER));

    private final MigrationPlanRepository plans;
    private final MigrationCustomerRepository customers;
    private final CandidateDiscovery discovery;
    private final TriggerScanner triggerScanner;
    private final SimulationClient simulations;
    private final OrderingClient ordering;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final Json json;
    private final Clock clock;
    private final int noticeFloorDays;
    private final boolean compressClocks;

    public MigrationPlanService(MigrationPlanRepository plans, MigrationCustomerRepository customers,
            CandidateDiscovery discovery, TriggerScanner triggerScanner, SimulationClient simulations,
            OrderingClient ordering, DomainEventPublisher events, TenantScope tenantScope,
            Json json, Clock clock,
            @Value("${bss.migration.notice-floor-days:30}") int noticeFloorDays,
            @Value("${bss.migration.compress-clocks:false}") boolean compressClocks) {
        this.plans = plans;
        this.customers = customers;
        this.discovery = discovery;
        this.triggerScanner = triggerScanner;
        this.simulations = simulations;
        this.ordering = ordering;
        this.events = events;
        this.tenantScope = tenantScope;
        this.json = json;
        this.clock = clock;
        this.noticeFloorDays = noticeFloorDays;
        this.compressClocks = compressClocks;
    }

    // ---- plan CRUD ----

    @Transactional
    public MigrationPlanView create(MigrationPlanRequest dto) {
        MigrationPlan plan = new MigrationPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setTenantId(tenantScope.currentTenantId());
        plan.setHref(ApiConstants.BASE_PATH + "/migrationPlan/" + plan.getId());
        plan.setState(MigrationPlan.DRAFT);
        plan.setCreatedAt(OffsetDateTime.now(clock));
        applyAndValidate(plan, dto, true);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        return toMap(plans.save(plan));
    }

    @Transactional(readOnly = true)
    public PagedResult<MigrationPlanView> list(int offset, int limit) {
        List<MigrationPlan> all = plans.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        List<MigrationPlanView> page = all.stream().skip(offset).limit(limit).map(this::toMap).toList();
        return new PagedResult<>(page, all.size());
    }

    @Transactional(readOnly = true)
    public MigrationPlanView get(String id) {
        return toMap(find(id));
    }

    @Transactional
    public MigrationPlanView patch(String id, MigrationPlanRequest dto) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.DRAFT.equals(plan.getState())
                && !MigrationPlan.SIMULATED.equals(plan.getState())) {
            throw new ConflictException("plan '" + id + "' is " + plan.getState()
                    + " — only draft/simulated plans can be edited");
        }
        applyAndValidate(plan, dto, false);
        if (MigrationPlan.SIMULATED.equals(plan.getState()) && dto.touchesSubstance()) {
            // the substance changed under the rehearsal — the receipt is stale
            plan.setState(MigrationPlan.DRAFT);
            plan.setSimulationRef(null);
            plan.setSimulationAttachedAt(null);
        }
        plan.setLastUpdate(OffsetDateTime.now(clock));
        return toMap(plans.save(plan));
    }

    @Transactional
    public void delete(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.DRAFT.equals(plan.getState())) {
            throw new ConflictException("only draft plans can be deleted");
        }
        plans.delete(plan);
    }

    // ---- the rehearsal gate ----

    @Transactional
    public MigrationPlanView attachSimulation(String id, AttachSimulationRequest body) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.DRAFT.equals(plan.getState())
                && !MigrationPlan.SIMULATED.equals(plan.getState())) {
            throw new ConflictException("plan '" + id + "' is " + plan.getState()
                    + " — a simulation attaches before arming");
        }
        String ref = body.ref();
        if (ref == null || ref.isBlank()) {
            throw new BadRequestException("simulationRef is required");
        }
        if (!simulations.simulationExists(ref)) {
            throw new BadRequestException("simulationRef '" + ref
                    + "' is not a saved report on the pricing simulator");
        }
        plan.setSimulationRef(ref);
        plan.setSimulationAttachedAt(OffsetDateTime.now(clock));
        plan.setState(MigrationPlan.SIMULATED);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        return toMap(plans.save(plan));
    }

    @Transactional
    public MigrationPlanView arm(String id) {
        MigrationPlan plan = find(id);
        if (plan.getSimulationRef() == null || !MigrationPlan.SIMULATED.equals(plan.getState())) {
            // SIMULATE FIRST is codified, not advised
            throw new ConflictException("plan '" + id + "' cannot arm without an attached simulation "
                    + "(POST /migrationPlan/" + id + "/attachSimulation first)");
        }
        int discovered = 0;
        if (MigrationPlan.TRIGGER_BULK.equals(plan.getTriggerType())) {
            discovered = discovery.discoverBulk(plan, false, OffsetDateTime.now(clock));
        }
        plan.setState(MigrationPlan.ARMED);
        plan.setConsecutiveFailures(0);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        events.publish("MigrationPlanArmedEvent", RESOURCE,
                MigrationEvents.PlanEvent.of(plans.save(plan)).withDiscovered(discovered));
        return toMap(plan).withDiscovered(discovered);
    }

    @Transactional
    public MigrationPlanView pause(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.ARMED.equals(plan.getState())
                && !MigrationPlan.RUNNING.equals(plan.getState())) {
            throw new ConflictException("only an armed/running plan can pause");
        }
        plan.setState(MigrationPlan.PAUSED);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        events.publish("MigrationWavePausedEvent", RESOURCE, MigrationEvents.PlanEvent.of(plan));
        return toMap(plans.save(plan));
    }

    @Transactional
    public MigrationPlanView resume(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.PAUSED.equals(plan.getState())) {
            throw new ConflictException("only a paused plan can resume");
        }
        plan.setState(MigrationPlan.RUNNING);
        plan.setConsecutiveFailures(0);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        events.publish("MigrationWaveStartedEvent", RESOURCE, MigrationEvents.PlanEvent.of(plan));
        return toMap(plans.save(plan));
    }

    @Transactional
    public TriggerScanResult scanTriggers(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.ARMED.equals(plan.getState())
                && !MigrationPlan.RUNNING.equals(plan.getState())) {
            throw new ConflictException("triggers scan only for an armed/running plan");
        }
        return triggerScanner.scanPlan(plan);
    }

    // ---- customers ----

    @Transactional(readOnly = true)
    public PagedResult<MigrationCustomerDetail> customers(String planId, String state, int offset, int limit) {
        MigrationPlan plan = find(planId);
        List<MigrationCustomer> all = state == null || state.isBlank()
                ? customers.findByTenantIdAndPlanIdOrderByCreatedAtAsc(plan.getTenantId(), plan.getId())
                : customers.findByTenantIdAndPlanIdAndStateOrderByCreatedAtAsc(
                        plan.getTenantId(), plan.getId(), state);
        List<MigrationCustomerDetail> page = all.stream().skip(offset).limit(limit)
                .map(this::customerToMap).toList();
        return new PagedResult<>(page, all.size());
    }

    /** The exercised exit: recorded penalty-free where the right applies;
     *  the actual termination is the ordering side's, downstream of the event. */
    @Transactional
    public MigrationCustomerDetail exit(String planId, String customerId) {
        MigrationPlan plan = find(planId);
        MigrationCustomer customer = findCustomer(plan, customerId);
        if (!Set.of(MigrationCustomer.SCHEDULED, MigrationCustomer.NOTICED,
                MigrationCustomer.EXIT_WINDOW).contains(customer.getState())) {
            throw new ConflictException("customer is " + customer.getState()
                    + " — the exit window has closed");
        }
        customer.setState(MigrationCustomer.EXITED);
        customer.setPenaltyFreeExit(customer.isPenaltyFreeExit() || customer.isExitRight());
        customer.setLastUpdate(OffsetDateTime.now(clock));
        customers.save(customer);
        events.publish("MigrationExitExercisedEvent", "migrationCustomer",
                MigrationCustomerView.of(customer));
        return customerToMap(customer);
    }

    /** The inverse modify order, from the pre-migration snapshot. */
    @Transactional
    public MigrationCustomerDetail rollback(String planId, String customerId) {
        MigrationPlan plan = find(planId);
        MigrationCustomer customer = findCustomer(plan, customerId);
        if (!MigrationCustomer.MIGRATED.equals(customer.getState())) {
            throw new ConflictException("only a migrated customer can roll back");
        }
        JsonNode offering = json.readObject(customer.getSnapshotJson()).get("productOffering");
        if (offering == null || !offering.isObject() || !offering.hasNonNull("id")) {
            throw new ConflictException("no usable pre-migration snapshot for customer '"
                    + customerId + "'");
        }
        JsonNode order = ordering.placeModifyOrder(
                customer.getPartyId(), customer.getProductId(),
                MigrationPlanRequest.text(offering.get("id")),
                offering.hasNonNull("name") ? MigrationPlanRequest.text(offering.get("name")) : null,
                Map.of(),
                "rollback of base migration '" + plan.getName() + "' (" + plan.getId() + ")");
        customer.setRollbackOrderRef(order == null ? null : MigrationPlanRequest.text(order.get("id")));
        customer.setState(MigrationCustomer.ROLLED_BACK);
        customer.setLastUpdate(OffsetDateTime.now(clock));
        customers.save(customer);
        events.publish("CustomerMigrationRolledBackEvent", "migrationCustomer",
                MigrationCustomerView.of(customer));
        return customerToMap(customer);
    }

    @Transactional(readOnly = true)
    public MigrationProgress progress(String planId) {
        MigrationPlan plan = find(planId);
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (String state : List.of(MigrationCustomer.SCHEDULED, MigrationCustomer.NOTICED,
                MigrationCustomer.EXIT_WINDOW, MigrationCustomer.ORDER_EMITTED,
                MigrationCustomer.MIGRATED, MigrationCustomer.EXITED,
                MigrationCustomer.FAILED, MigrationCustomer.ROLLED_BACK)) {
            long count = customers.countByTenantIdAndPlanIdAndStateIn(
                    plan.getTenantId(), plan.getId(), List.of(state));
            counts.put(state, count);
            total += count;
        }
        return new MigrationProgress(plan.getId(), plan.getName(), plan.getState(),
                plan.getConsecutiveFailures(), total, counts,
                json.readStrings(plan.getGrandfatheredJson()));
    }

    // ---- internals ----

    private MigrationPlan find(String id) {
        return plans.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
    }

    private MigrationCustomer findCustomer(MigrationPlan plan, String customerId) {
        return customers.findByIdAndTenantIdAndPlanId(customerId, plan.getTenantId(), plan.getId())
                .orElseThrow(() -> NotFoundException.forResource("migrationCustomer", customerId));
    }

    private void applyAndValidate(MigrationPlan plan, MigrationPlanRequest dto, boolean creating) {
        if (MigrationPlanRequest.given(dto.name())) {
            plan.setName(MigrationPlanRequest.text(dto.name()));
        }
        if (creating && (plan.getName() == null || plan.getName().isBlank())) {
            throw new BadRequestException("name is required");
        }

        if (MigrationPlanRequest.given(dto.matrix()) || creating) {
            JsonNode rawMatrix = dto.matrix();
            if (rawMatrix == null || !rawMatrix.isArray() || rawMatrix.isEmpty()) {
                throw new BadRequestException(
                        "matrix [{sourceOfferingId, targetOfferingId, deltaClass}] is required");
            }
            JsonNode matrix = rawMatrix.deepCopy();
            for (JsonNode raw : matrix) {
                // a row that is not an object fails here exactly as the map cast did
                ObjectNode row = (ObjectNode) raw;
                if (!row.hasNonNull("sourceOfferingId") || !row.hasNonNull("targetOfferingId")) {
                    throw new BadRequestException(
                            "every matrix row needs sourceOfferingId and targetOfferingId");
                }
                JsonNode deltaClass = row.has("deltaClass") ? row.get("deltaClass") : null;
                String named = deltaClass == null ? "neutral" : MigrationPlanRequest.text(deltaClass);
                if (!DELTA_CLASSES.contains(named)) {
                    throw new BadRequestException("deltaClass must be one of " + DELTA_CLASSES);
                }
                row.set("deltaClass", deltaClass == null ? TextNode.valueOf("neutral") : deltaClass);
            }
            plan.setMatrixJson(json.write(matrix));
        }

        if (MigrationPlanRequest.given(dto.eligibility()) || (creating && plan.getEligibilityJson() == null)) {
            ObjectNode eligibility = objectOrEmpty(dto.eligibility());
            String inBinding = eligibility.has("inBinding")
                    ? MigrationPlanRequest.text(eligibility.get("inBinding"))
                    : CandidateDiscovery.IN_BINDING_DEFER;
            if (!IN_BINDING.contains(inBinding)) {
                throw new BadRequestException("eligibility.inBinding must be one of " + IN_BINDING);
            }
            eligibility.put("inBinding", inBinding);
            plan.setEligibilityJson(json.write(eligibility));
        }

        if (MigrationPlanRequest.given(dto.trigger()) || creating) {
            ObjectNode trigger = objectOrEmpty(dto.trigger());
            String type = trigger.has("type")
                    ? MigrationPlanRequest.text(trigger.get("type")) : MigrationPlan.TRIGGER_BULK;
            if (!TRIGGERS.contains(type)) {
                throw new BadRequestException("trigger.type must be one of " + TRIGGERS);
            }
            if (MigrationPlan.TRIGGER_AGE.equals(type)) {
                JsonNode ageYears = trigger.get("ageYears");
                if (ageYears == null || !ageYears.isNumber() || ageYears.intValue() <= 0) {
                    throw new BadRequestException("an age-threshold trigger needs ageYears > 0");
                }
                String strategy = trigger.has("strategy")
                        ? MigrationPlanRequest.text(trigger.get("strategy")) : TriggerScanner.STRATEGY_AUTO;
                if (!AGE_STRATEGIES.contains(strategy)) {
                    throw new BadRequestException("trigger.strategy must be one of " + AGE_STRATEGIES);
                }
                trigger.put("strategy", strategy);
            }
            if (MigrationPlan.TRIGGER_PROMO.equals(type)) {
                try {
                    LocalDate.parse(MigrationPlanRequest.text(trigger.get("endDate")));
                } catch (DateTimeParseException | NullPointerException e) {
                    throw new BadRequestException(
                            "a promo-expiry trigger needs an explicit endDate (YYYY-MM-DD)");
                }
            }
            plan.setTriggerType(type);
            plan.setTriggerJson(json.write(trigger));
        }

        if (MigrationPlanRequest.given(dto.jurisdictionPack()) || creating) {
            ObjectNode pack = objectOrEmpty(dto.jurisdictionPack());
            JsonNode days = pack.get("noticeDays");
            int noticeDays = days != null && days.isNumber() ? days.intValue() : 30;
            if (noticeDays < 0) {
                throw new BadRequestException("noticeDays must be >= 0");
            }
            if (noticeDays < noticeFloorDays && !compressClocks) {
                // the one-month floor is law (EECC Art. 105); only the documented
                // test/demo property may compress it
                throw new BadRequestException("noticeDays " + noticeDays + " is below the jurisdiction "
                        + "floor of " + noticeFloorDays
                        + " days (set bss.migration.compress-clocks=true only for tests/demo)");
            }
            pack.put("noticeDays", noticeDays);
            plan.setNoticeDays(noticeDays);
            plan.setJurisdictionJson(json.write(pack));
        }

        if (MigrationPlanRequest.given(dto.maxOrdersPerRun()) && dto.maxOrdersPerRun().isNumber()) {
            if (dto.maxOrdersPerRun().intValue() < 1) {
                throw new BadRequestException("maxOrdersPerRun must be >= 1");
            }
            plan.setMaxOrdersPerRun(dto.maxOrdersPerRun().intValue());
        } else if (creating) {
            plan.setMaxOrdersPerRun(10);
        }
        if (MigrationPlanRequest.given(dto.breakerThreshold()) && dto.breakerThreshold().isNumber()) {
            if (dto.breakerThreshold().intValue() < 1) {
                throw new BadRequestException("breakerThreshold must be >= 1");
            }
            plan.setBreakerThreshold(dto.breakerThreshold().intValue());
        } else if (creating) {
            plan.setBreakerThreshold(3);
        }
    }

    /** The operator's block, copied so validation never writes back into the request. */
    private ObjectNode objectOrEmpty(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : json.newObject();
    }

    private MigrationPlanView toMap(MigrationPlan plan) {
        return new MigrationPlanView(plan.getId(), plan.getHref(), plan.getName(), plan.getState(),
                json.readArray(plan.getMatrixJson()),
                json.readObject(plan.getEligibilityJson()),
                json.readObject(plan.getTriggerJson()),
                json.readObject(plan.getJurisdictionJson()),
                plan.getNoticeDays(), json.readStrings(plan.getGrandfatheredJson()),
                plan.getSimulationRef(),
                plan.getSimulationAttachedAt() == null ? null : plan.getSimulationAttachedAt().toString(),
                plan.getMaxOrdersPerRun(), plan.getBreakerThreshold(), plan.getConsecutiveFailures(),
                plan.getCreatedAt() == null ? null : plan.getCreatedAt().toString(),
                plan.getLastUpdate() == null ? null : plan.getLastUpdate().toString(),
                "MigrationPlan", null);
    }

    private MigrationCustomerDetail customerToMap(MigrationCustomer customer) {
        return new MigrationCustomerDetail(MigrationCustomerView.of(customer),
                customer.getRollbackOrderRef(),
                json.readObject(customer.getSnapshotJson()),
                customer.getCreatedAt() == null ? null : customer.getCreatedAt().toString(),
                "MigrationCustomer");
    }
}
