package com.bss.basemigration.service;

import com.bss.basemigration.client.OrderingClient;
import com.bss.basemigration.dto.MigrationCustomerView;
import com.bss.basemigration.dto.MigrationEvents;
import com.bss.basemigration.entity.MigrationCustomer;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.events.DomainEventPublisher;
import com.bss.basemigration.repository.MigrationCustomerRepository;
import com.bss.basemigration.repository.MigrationPlanRepository;
import com.bss.basemigration.security.TenantContext;
import com.bss.basemigration.security.TenantRegistry;
import com.bss.basemigration.tick.TickGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wave: for every armed/running plan, send the notices whose time has
 * come, then — and this is a GATE, not a convention — emit the TMF622
 * modify order no earlier than noticeSentAt + noticeDays. Wave controls:
 * maxOrdersPerRun caps the blast radius per tick, and a consecutive-failure
 * circuit breaker pauses the plan before a systematic error walks the
 * whole cohort into the ditch (the £4.6M lesson, made executable).
 */
@Component
public class MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);
    private static final List<String> PENDING =
            List.of(MigrationCustomer.SCHEDULED, MigrationCustomer.NOTICED, MigrationCustomer.EXIT_WINDOW);

    private final MigrationPlanRepository plans;
    private final MigrationCustomerRepository customers;
    private final OrderingClient ordering;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final TickGuard tickGuard;
    private final Json json;
    private final Clock clock;

    public MigrationEngine(MigrationPlanRepository plans, MigrationCustomerRepository customers,
            OrderingClient ordering, DomainEventPublisher events, TenantRegistry tenants,
            TickGuard tickGuard, Json json, Clock clock) {
        this.plans = plans;
        this.customers = customers;
        this.ordering = ordering;
        this.events = events;
        this.tenants = tenants;
        this.tickGuard = tickGuard;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bss.migration.tick-ms:5000}")
    public void tick() {
        if (!tickGuard.claim("base-migration-wave", Duration.ofSeconds(60))) {
            return; // another replica runs the wave — a customer migrates once
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    runTenant(tenant.getId());
                } catch (Exception e) {
                    log.warn("migration wave skipped tenant '{}': {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("base-migration-wave");
        }
    }

    /** One tenant's wave. Public so tests drive it under an explicit TenantContext. */
    public void runTenant(String tenantId) {
        for (MigrationPlan plan : plans.findByTenantIdAndStateIn(tenantId,
                List.of(MigrationPlan.ARMED, MigrationPlan.RUNNING))) {
            if (MigrationPlan.ARMED.equals(plan.getState())) {
                plan.setState(MigrationPlan.RUNNING);
                plan.setLastUpdate(OffsetDateTime.now(clock));
                plans.save(plan);
                events.publish("MigrationWaveStartedEvent", "migrationPlan",
                        MigrationEvents.PlanEvent.of(plan), tenantId);
            }
            sendDueNotices(plan);
            emitDueOrders(plan);
            closeIfDone(plan);
        }
    }

    /** The written notice: what changes, when, and whether the customer may
     *  leave penalty-free. Delivery rides the journey/communication side off
     *  this event — the engine's duty is the record and the clock. */
    private void sendDueNotices(MigrationPlan plan) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (MigrationCustomer customer : customers.findByTenantIdAndPlanIdAndStateOrderByCreatedAtAsc(
                plan.getTenantId(), plan.getId(), MigrationCustomer.SCHEDULED)) {
            if (customer.getScheduledFor() != null && customer.getScheduledFor().isAfter(now)) {
                continue;
            }
            customer.setNoticeSentAt(now);
            customer.setState(customer.isExitRight()
                    ? MigrationCustomer.EXIT_WINDOW : MigrationCustomer.NOTICED);
            customer.setLastUpdate(now);
            customers.save(customer);
            events.publish("CustomerMigrationNoticedEvent", "migrationCustomer",
                    new MigrationEvents.CustomerNoticed(MigrationCustomerView.of(customer),
                            now.plusDays(plan.getNoticeDays()).toString(), changeSummary(customer)),
                    plan.getTenantId());
        }
    }

    /** The hard gate and the one production write: a TMF622 modify order per
     *  subscriber, never before the notice window has run. */
    private void emitDueOrders(MigrationPlan plan) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int emitted = 0;
        for (MigrationCustomer customer : customers.findByTenantIdAndPlanIdAndStateInOrderByCreatedAtAsc(
                plan.getTenantId(), plan.getId(),
                List.of(MigrationCustomer.NOTICED, MigrationCustomer.EXIT_WINDOW))) {
            if (emitted >= plan.getMaxOrdersPerRun()) {
                break;
            }
            if (customer.getNoticeSentAt() == null
                    || customer.getNoticeSentAt().plusDays(plan.getNoticeDays()).isAfter(now)) {
                continue; // THE GATE: the law's month is the engine's inequality
            }
            emitted++;
            customer.setState(MigrationCustomer.ORDER_EMITTED);
            customer.setLastUpdate(now);
            customers.save(customer);
            try {
                JsonNode order = ordering.placeModifyOrder(
                        customer.getPartyId(), customer.getProductId(),
                        customer.getTargetOfferingId(), customer.getTargetOfferingName(),
                        characteristicMapFor(plan, customer),
                        "base migration '" + plan.getName() + "' (" + plan.getId() + ")");
                customer.setOrderRef(order == null ? null
                        : com.bss.basemigration.dto.MigrationPlanRequest.text(order.get("id")));
                customer.setState(MigrationCustomer.MIGRATED);
                customer.setLastUpdate(OffsetDateTime.now(clock));
                customers.save(customer);
                plan.setConsecutiveFailures(0);
                plans.save(plan);
                events.publish("CustomerMigrationCompletedEvent", "migrationCustomer",
                        MigrationCustomerView.of(customer), plan.getTenantId());
            } catch (Exception e) {
                customer.setState(MigrationCustomer.FAILED);
                customer.setFailureReason(abbreviate(e.getMessage()));
                customer.setLastUpdate(OffsetDateTime.now(clock));
                customers.save(customer);
                events.publish("CustomerMigrationFailedEvent", "migrationCustomer",
                        MigrationCustomerView.of(customer), plan.getTenantId());
                plan.setConsecutiveFailures(plan.getConsecutiveFailures() + 1);
                if (plan.getConsecutiveFailures() >= plan.getBreakerThreshold()) {
                    // the circuit breaker: stop the wave, keep the evidence
                    plan.setState(MigrationPlan.PAUSED);
                    plan.setLastUpdate(OffsetDateTime.now(clock));
                    plans.save(plan);
                    events.publish("MigrationWavePausedEvent", "migrationPlan",
                            MigrationEvents.PlanEvent.of(plan), plan.getTenantId());
                    log.warn("plan '{}' PAUSED: {} consecutive order failures",
                            plan.getName(), plan.getConsecutiveFailures());
                    return;
                }
                plans.save(plan);
            }
        }
    }

    /** A bulk plan is done when nobody is left mid-journey; trigger plans
     *  stay running — birthdays and roll-offs keep coming. */
    private void closeIfDone(MigrationPlan plan) {
        if (!MigrationPlan.RUNNING.equals(plan.getState())
                || !MigrationPlan.TRIGGER_BULK.equals(plan.getTriggerType())) {
            return;
        }
        long pending = customers.countByTenantIdAndPlanIdAndStateIn(
                plan.getTenantId(), plan.getId(), PENDING);
        long total = customers.countByTenantIdAndPlanId(plan.getTenantId(), plan.getId());
        if (pending == 0 && total > 0) {
            plan.setState(MigrationPlan.DONE);
            plan.setLastUpdate(OffsetDateTime.now(clock));
            plans.save(plan);
        }
    }

    private Map<String, Object> characteristicMapFor(MigrationPlan plan, MigrationCustomer customer) {
        // matrix row for this customer's source offering carries the carry-over map
        for (JsonNode row : json.readArray(plan.getMatrixJson())) {
            JsonNode chars = row.get("characteristicMap");
            if (com.bss.basemigration.dto.MigrationPlanRequest.text(row.get("sourceOfferingId"))
                    .equals(customer.getSourceOfferingId()) && chars != null && chars.isObject()) {
                Map<String, Object> out = new LinkedHashMap<>();
                chars.fields().forEachRemaining(e -> out.put(e.getKey(),
                        e.getValue().isTextual() ? e.getValue().textValue() : e.getValue()));
                return out;
            }
        }
        return Map.of();
    }

    private static String changeSummary(MigrationCustomer customer) {
        String source = customer.getSourceOfferingName() != null
                ? customer.getSourceOfferingName() : customer.getSourceOfferingId();
        String target = customer.getTargetOfferingName() != null
                ? customer.getTargetOfferingName() : customer.getTargetOfferingId();
        return "Your plan '" + source + "' is changing to '" + target + "' (" + customer.getDeltaClass()
                + " change)." + (customer.isExitRight()
                        ? " You may cancel penalty-free before the change takes effect." : "");
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "order emission failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
