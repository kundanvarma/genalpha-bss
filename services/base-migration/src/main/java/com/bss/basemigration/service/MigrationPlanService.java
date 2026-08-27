package com.bss.basemigration.service;

import com.bss.basemigration.api.ApiConstants;
import com.bss.basemigration.api.PagedResult;
import com.bss.basemigration.client.OrderingClient;
import com.bss.basemigration.client.SimulationClient;
import com.bss.basemigration.entity.MigrationCustomer;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.events.DomainEventPublisher;
import com.bss.basemigration.exception.BadRequestException;
import com.bss.basemigration.exception.ConflictException;
import com.bss.basemigration.exception.NotFoundException;
import com.bss.basemigration.repository.MigrationCustomerRepository;
import com.bss.basemigration.repository.MigrationPlanRepository;
import com.bss.basemigration.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
    private static final Set<String> DELTA_CLASSES = Set.of("beneficial", "neutral", "detrimental");
    private static final Set<String> IN_BINDING = Set.of(CandidateDiscovery.IN_BINDING_DEFER,
            CandidateDiscovery.IN_BINDING_EXCLUDE, CandidateDiscovery.IN_BINDING_FREE_EXIT);
    private static final Set<String> TRIGGERS = Set.of(MigrationPlan.TRIGGER_BULK,
            MigrationPlan.TRIGGER_AGE, MigrationPlan.TRIGGER_PROMO);
    private static final Set<String> AGE_STRATEGIES = Set.of(TriggerScanner.STRATEGY_AUTO,
            TriggerScanner.STRATEGY_GRANDFATHER);

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
    public Map<String, Object> create(Map<String, Object> dto) {
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
    public PagedResult<Map<String, Object>> list(int offset, int limit) {
        List<MigrationPlan> all = plans.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId());
        List<Map<String, Object>> page = all.stream().skip(offset).limit(limit).map(this::toMap).toList();
        return new PagedResult<>(page, all.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toMap(find(id));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> dto) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.DRAFT.equals(plan.getState())
                && !MigrationPlan.SIMULATED.equals(plan.getState())) {
            throw new ConflictException("plan '" + id + "' is " + plan.getState()
                    + " — only draft/simulated plans can be edited");
        }
        applyAndValidate(plan, dto, false);
        if (MigrationPlan.SIMULATED.equals(plan.getState())
                && (dto.containsKey("matrix") || dto.containsKey("eligibility")
                        || dto.containsKey("trigger") || dto.containsKey("jurisdictionPack"))) {
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
    public Map<String, Object> attachSimulation(String id, Map<String, Object> body) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.DRAFT.equals(plan.getState())
                && !MigrationPlan.SIMULATED.equals(plan.getState())) {
            throw new ConflictException("plan '" + id + "' is " + plan.getState()
                    + " — a simulation attaches before arming");
        }
        String ref = body.get("simulationRef") == null ? null : String.valueOf(body.get("simulationRef"));
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
    public Map<String, Object> arm(String id) {
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
        Map<String, Object> resource = MigrationEngine.planResource(plans.save(plan));
        resource.put("customersDiscovered", discovered);
        events.publish("MigrationPlanArmedEvent", RESOURCE, resource);
        Map<String, Object> out = toMap(plan);
        out.put("customersDiscovered", discovered);
        return out;
    }

    @Transactional
    public Map<String, Object> pause(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.ARMED.equals(plan.getState())
                && !MigrationPlan.RUNNING.equals(plan.getState())) {
            throw new ConflictException("only an armed/running plan can pause");
        }
        plan.setState(MigrationPlan.PAUSED);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        events.publish("MigrationWavePausedEvent", RESOURCE, MigrationEngine.planResource(plan));
        return toMap(plans.save(plan));
    }

    @Transactional
    public Map<String, Object> resume(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.PAUSED.equals(plan.getState())) {
            throw new ConflictException("only a paused plan can resume");
        }
        plan.setState(MigrationPlan.RUNNING);
        plan.setConsecutiveFailures(0);
        plan.setLastUpdate(OffsetDateTime.now(clock));
        events.publish("MigrationWaveStartedEvent", RESOURCE, MigrationEngine.planResource(plan));
        return toMap(plans.save(plan));
    }

    @Transactional
    public Map<String, Object> scanTriggers(String id) {
        MigrationPlan plan = find(id);
        if (!MigrationPlan.ARMED.equals(plan.getState())
                && !MigrationPlan.RUNNING.equals(plan.getState())) {
            throw new ConflictException("triggers scan only for an armed/running plan");
        }
        return triggerScanner.scanPlan(plan);
    }

    // ---- customers ----

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> customers(String planId, String state, int offset, int limit) {
        MigrationPlan plan = find(planId);
        List<MigrationCustomer> all = state == null || state.isBlank()
                ? customers.findByTenantIdAndPlanIdOrderByCreatedAtAsc(plan.getTenantId(), plan.getId())
                : customers.findByTenantIdAndPlanIdAndStateOrderByCreatedAtAsc(
                        plan.getTenantId(), plan.getId(), state);
        List<Map<String, Object>> page = all.stream().skip(offset).limit(limit)
                .map(this::customerToMap).toList();
        return new PagedResult<>(page, all.size());
    }

    /** The exercised exit: recorded penalty-free where the right applies;
     *  the actual termination is the ordering side's, downstream of the event. */
    @Transactional
    public Map<String, Object> exit(String planId, String customerId) {
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
                MigrationEngine.customerResource(customer));
        return customerToMap(customer);
    }

    /** The inverse modify order, from the pre-migration snapshot. */
    @Transactional
    public Map<String, Object> rollback(String planId, String customerId) {
        MigrationPlan plan = find(planId);
        MigrationCustomer customer = findCustomer(plan, customerId);
        if (!MigrationCustomer.MIGRATED.equals(customer.getState())) {
            throw new ConflictException("only a migrated customer can roll back");
        }
        Map<String, Object> snapshot = json.readMap(customer.getSnapshotJson());
        if (!(snapshot.get("productOffering") instanceof Map<?, ?> offering)
                || offering.get("id") == null) {
            throw new ConflictException("no usable pre-migration snapshot for customer '"
                    + customerId + "'");
        }
        Map<String, Object> order = ordering.placeModifyOrder(
                customer.getPartyId(), customer.getProductId(),
                String.valueOf(offering.get("id")),
                offering.get("name") == null ? null : String.valueOf(offering.get("name")),
                Map.of(),
                "rollback of base migration '" + plan.getName() + "' (" + plan.getId() + ")");
        customer.setRollbackOrderRef(order == null ? null : String.valueOf(order.get("id")));
        customer.setState(MigrationCustomer.ROLLED_BACK);
        customer.setLastUpdate(OffsetDateTime.now(clock));
        customers.save(customer);
        events.publish("CustomerMigrationRolledBackEvent", "migrationCustomer",
                MigrationEngine.customerResource(customer));
        return customerToMap(customer);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> progress(String planId) {
        MigrationPlan plan = find(planId);
        Map<String, Object> counts = new LinkedHashMap<>();
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.getId());
        out.put("name", plan.getName());
        out.put("state", plan.getState());
        out.put("consecutiveFailures", plan.getConsecutiveFailures());
        out.put("totalCustomers", total);
        out.put("byState", counts);
        out.put("grandfatheredPartyIds", json.readStrings(plan.getGrandfatheredJson()));
        return out;
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

    @SuppressWarnings("unchecked")
    private void applyAndValidate(MigrationPlan plan, Map<String, Object> dto, boolean creating) {
        if (dto.get("name") != null) {
            plan.setName(String.valueOf(dto.get("name")));
        }
        if (creating && (plan.getName() == null || plan.getName().isBlank())) {
            throw new BadRequestException("name is required");
        }

        if (dto.get("matrix") != null || creating) {
            if (!(dto.get("matrix") instanceof List<?> rawMatrix) || rawMatrix.isEmpty()) {
                throw new BadRequestException(
                        "matrix [{sourceOfferingId, targetOfferingId, deltaClass}] is required");
            }
            for (Object raw : rawMatrix) {
                Map<String, Object> row = (Map<String, Object>) raw;
                if (row.get("sourceOfferingId") == null || row.get("targetOfferingId") == null) {
                    throw new BadRequestException(
                            "every matrix row needs sourceOfferingId and targetOfferingId");
                }
                Object deltaClass = row.getOrDefault("deltaClass", "neutral");
                if (!DELTA_CLASSES.contains(String.valueOf(deltaClass))) {
                    throw new BadRequestException("deltaClass must be one of " + DELTA_CLASSES);
                }
                row.put("deltaClass", deltaClass);
            }
            plan.setMatrixJson(json.write(rawMatrix));
        }

        if (dto.get("eligibility") != null || (creating && plan.getEligibilityJson() == null)) {
            Map<String, Object> eligibility = dto.get("eligibility") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            String inBinding = String.valueOf(
                    eligibility.getOrDefault("inBinding", CandidateDiscovery.IN_BINDING_DEFER));
            if (!IN_BINDING.contains(inBinding)) {
                throw new BadRequestException("eligibility.inBinding must be one of " + IN_BINDING);
            }
            eligibility.put("inBinding", inBinding);
            plan.setEligibilityJson(json.write(eligibility));
        }

        if (dto.get("trigger") != null || creating) {
            Map<String, Object> trigger = dto.get("trigger") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            String type = String.valueOf(trigger.getOrDefault("type", MigrationPlan.TRIGGER_BULK));
            if (!TRIGGERS.contains(type)) {
                throw new BadRequestException("trigger.type must be one of " + TRIGGERS);
            }
            if (MigrationPlan.TRIGGER_AGE.equals(type)) {
                if (!(trigger.get("ageYears") instanceof Number n) || n.intValue() <= 0) {
                    throw new BadRequestException("an age-threshold trigger needs ageYears > 0");
                }
                String strategy = String.valueOf(
                        trigger.getOrDefault("strategy", TriggerScanner.STRATEGY_AUTO));
                if (!AGE_STRATEGIES.contains(strategy)) {
                    throw new BadRequestException("trigger.strategy must be one of " + AGE_STRATEGIES);
                }
                trigger.put("strategy", strategy);
            }
            if (MigrationPlan.TRIGGER_PROMO.equals(type)) {
                try {
                    LocalDate.parse(String.valueOf(trigger.get("endDate")));
                } catch (DateTimeParseException | NullPointerException e) {
                    throw new BadRequestException(
                            "a promo-expiry trigger needs an explicit endDate (YYYY-MM-DD)");
                }
            }
            plan.setTriggerType(type);
            plan.setTriggerJson(json.write(trigger));
        }

        if (dto.get("jurisdictionPack") != null || creating) {
            Map<String, Object> pack = dto.get("jurisdictionPack") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            int noticeDays = pack.get("noticeDays") instanceof Number n ? n.intValue() : 30;
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

        if (dto.get("maxOrdersPerRun") instanceof Number n) {
            if (n.intValue() < 1) {
                throw new BadRequestException("maxOrdersPerRun must be >= 1");
            }
            plan.setMaxOrdersPerRun(n.intValue());
        } else if (creating) {
            plan.setMaxOrdersPerRun(10);
        }
        if (dto.get("breakerThreshold") instanceof Number n) {
            if (n.intValue() < 1) {
                throw new BadRequestException("breakerThreshold must be >= 1");
            }
            plan.setBreakerThreshold(n.intValue());
        } else if (creating) {
            plan.setBreakerThreshold(3);
        }
    }

    private Map<String, Object> toMap(MigrationPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.getId());
        out.put("href", plan.getHref());
        out.put("name", plan.getName());
        out.put("state", plan.getState());
        out.put("matrix", json.readList(plan.getMatrixJson()));
        out.put("eligibility", json.readMap(plan.getEligibilityJson()));
        out.put("trigger", json.readMap(plan.getTriggerJson()));
        out.put("jurisdictionPack", json.readMap(plan.getJurisdictionJson()));
        out.put("noticeDays", plan.getNoticeDays());
        out.put("grandfatheredPartyIds", json.readStrings(plan.getGrandfatheredJson()));
        out.put("simulationRef", plan.getSimulationRef());
        if (plan.getSimulationAttachedAt() != null) {
            out.put("simulationAttachedAt", plan.getSimulationAttachedAt().toString());
        }
        out.put("maxOrdersPerRun", plan.getMaxOrdersPerRun());
        out.put("breakerThreshold", plan.getBreakerThreshold());
        out.put("consecutiveFailures", plan.getConsecutiveFailures());
        out.put("createdAt", plan.getCreatedAt() == null ? null : plan.getCreatedAt().toString());
        out.put("lastUpdate", plan.getLastUpdate() == null ? null : plan.getLastUpdate().toString());
        out.put("@type", "MigrationPlan");
        return out;
    }

    private Map<String, Object> customerToMap(MigrationCustomer customer) {
        Map<String, Object> out = MigrationEngine.customerResource(customer);
        if (customer.getRollbackOrderRef() != null) {
            out.put("rollbackOrderRef", customer.getRollbackOrderRef());
        }
        out.put("snapshot", json.readMap(customer.getSnapshotJson()));
        out.put("createdAt", customer.getCreatedAt() == null ? null : customer.getCreatedAt().toString());
        out.put("@type", "MigrationCustomer");
        return out;
    }
}
