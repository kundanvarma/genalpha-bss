package com.bss.basemigration.dto;

import com.bss.basemigration.entity.MigrationPlan;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * The two payloads that ride the outbox and are not an API view: the plan as
 * the wave events name it, and the notice event — the customer's journey plus
 * the two facts the notice adds (when the order may go out at the earliest,
 * and what the customer is told, in their own language).
 */
public final class MigrationEvents {

    private MigrationEvents() {
    }

    /** {@code MigrationPlanArmedEvent}, {@code MigrationWaveStartedEvent}, {@code MigrationWavePausedEvent}. */
    @JsonPropertyOrder({"id", "name", "state", "triggerType", "noticeDays", "simulationRef",
        "consecutiveFailures", "customersDiscovered"})
    public record PlanEvent(
            String id,
            String name,
            String state,
            String triggerType,
            int noticeDays,
            String simulationRef,
            int consecutiveFailures,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer customersDiscovered) {

        public static PlanEvent of(MigrationPlan plan) {
            return new PlanEvent(plan.getId(), plan.getName(), plan.getState(), plan.getTriggerType(),
                    plan.getNoticeDays(), plan.getSimulationRef(), plan.getConsecutiveFailures(), null);
        }

        public PlanEvent withDiscovered(int discovered) {
            return new PlanEvent(id, name, state, triggerType, noticeDays, simulationRef,
                    consecutiveFailures, discovered);
        }
    }

    /** {@code CustomerMigrationNoticedEvent}: the journey plus the clock and the sentence. */
    @JsonPropertyOrder({"customer", "earliestOrderDate", "changeSummary"})
    public record CustomerNoticed(
            @JsonUnwrapped MigrationCustomerView customer,
            String earliestOrderDate,
            String changeSummary) {
    }
}
