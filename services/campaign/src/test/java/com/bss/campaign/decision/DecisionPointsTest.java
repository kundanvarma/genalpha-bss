package com.bss.campaign.decision;

import com.bss.campaign.decision.policy.HoldoutThenWeightedHashPolicy;
import com.bss.campaign.decision.policy.ZThresholdTunerPolicy;
import com.bss.campaign.events.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** The seam: constraints first, the policy next, a fallback always, one record per choice. */
class DecisionPointsTest {

    private final DomainEventPublisher events = Mockito.mock(DomainEventPublisher.class);
    private final DecisionPoints points = new DecisionPoints(events);

    @Test
    @SuppressWarnings("unchecked")
    void enrolmentSplitIsDeterministicCarriesPropensityAndIsPublished() {
        Map<String, Object> ctx = Map.of("seed", "j1", "partyId", "p-42", "holdoutPercent", 10,
                "weights", Map.of("A", 70, "B", 30));
        DecisionRecord first = points.decide(DecisionPoints.JOURNEY_ENROLMENT, "p-42", ctx,
                List.of("holdout", "A", "B"), List.of(), "holdout");
        DecisionRecord again = points.decide(DecisionPoints.JOURNEY_ENROLMENT, "p-42", ctx,
                List.of("holdout", "A", "B"), List.of(), "holdout");
        assertThat(first.action()).isEqualTo(again.action());
        assertThat(first.decisionId()).isNotEqualTo(again.decisionId());
        assertThat(first.propensity()).isNotNull();
        if ("holdout".equals(first.action())) {
            assertThat(first.propensity()).isEqualTo(0.10);
        } else {
            assertThat(first.propensity()).isEqualTo("A".equals(first.action()) ? 0.63 : 0.27);
        }
        assertThat(first.policy()).isEqualTo("holdout-then-weighted-hash");
        assertThat(first.fallback()).isFalse();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, Mockito.times(2)).publish(eq("DecisionRecordedEvent"), eq("decision"), payload.capture());
        Map<String, Object> published = (Map<String, Object>) payload.getAllValues().get(0);
        assertThat(published.get("decisionId")).isEqualTo(first.decisionId());
        assertThat(published.get("eligibleActions")).isEqualTo(List.of("holdout", "A", "B"));
        assertThat(published.get("autonomy")).isEqualTo("high");
    }

    @Test
    void theSplitMatchesTheHashesThatDealtEveryExistingEnrolment() {
        // the pre-seam rule: holdout by hash(seed+party) < percent; arm by bucket of hash(seed:party:arm)
        HoldoutThenWeightedHashPolicy policy = new HoldoutThenWeightedHashPolicy();
        for (int i = 0; i < 200; i++) {
            String party = "party-" + i;
            boolean holdout = Math.floorMod(("j9" + party).hashCode(), 100) < 20;
            int bucket = Math.floorMod(("j9:" + party + ":arm").hashCode(), 100);
            String expected = holdout ? "holdout" : bucket < 50 ? "A" : "B";
            Decision d = policy.decide(new DecisionRequest(DecisionPoints.JOURNEY_ENROLMENT, "party", party,
                    Map.of("seed", "j9", "partyId", party, "holdoutPercent", 20, "weights", Map.of("A", 50, "B", 50)),
                    List.of("holdout", "A", "B")));
            assertThat(d.action()).as(party).isEqualTo(expected);
        }
    }

    @Test
    void constraintsRemoveActionsBeforeThePolicySeesThem() {
        Constraint noB = new Constraint() {
            public String name() { return "channel-availability"; }
            public Optional<String> reject(String action, DecisionRequest r) {
                return "B".equals(action) ? Optional.of("not sellable on this channel") : Optional.empty();
            }
        };
        Map<String, Object> ctx = Map.of("seed", "j2", "partyId", "p-1", "holdoutPercent", 0, "weights", Map.of("A", 50, "B", 50));
        DecisionRecord r = points.decide(DecisionPoints.JOURNEY_ENROLMENT, "p-1", ctx, List.of("holdout", "A", "B"),
                List.of(noB), "holdout");
        assertThat(r.eligibleActions()).containsExactly("holdout", "A");
        assertThat(r.constraints()).hasSize(1);
        assertThat(r.constraints().get(0)).startsWith("channel-availability: B");
        assertThat(r.action()).isEqualTo("A");
    }

    @Test
    void fallbackAnswersWhenNothingIsEligibleOrThePolicyFails() {
        Constraint all = new Constraint() {
            public String name() { return "consent"; }
            public Optional<String> reject(String action, DecisionRequest r) { return Optional.of("no marketing consent"); }
        };
        DecisionRecord none = points.decide(DecisionPoints.JOURNEY_ENROLMENT, "p-1", Map.of("seed", "j3", "partyId", "p-1"),
                List.of("holdout", "A"), List.of(all), "holdout");
        assertThat(none.fallback()).isTrue();
        assertThat(none.action()).isEqualTo("holdout");
        assertThat(none.eligibleActions()).isEmpty();

        DecisionPolicy broken = new DecisionPolicy() {
            public String name() { return "broken"; }
            public String version() { return "0"; }
            public Decision decide(DecisionRequest r) { throw new IllegalStateException("model offline"); }
        };
        DecisionRecord failed = points.decide(points.spec(DecisionPoints.JOURNEY_ENROLMENT), broken, "p-1",
                Map.of(), List.of("A", "B"), List.of(), "A");
        assertThat(failed.fallback()).isTrue();
        assertThat(failed.action()).isEqualTo("A");
        assertThat(failed.reason()).contains("model offline");
        assertThat(failed.policy()).isEqualTo("broken");
    }

    @Test
    void tunerWaitsHoldsAndShiftsExactlyAsTheLedgerRuleSays() {
        ZThresholdTunerPolicy tuner = new ZThresholdTunerPolicy();
        Map<String, Object> thin = Map.of("rows", List.of(row("A", 5, 2), row("B", 30, 3)),
                "before", Map.of("A", 50, "B", 50), "minPerArm", 20, "floorPercent", 10, "threshold", 1.64);
        assertThat(tuner.decide(req(thin)).action()).isEqualTo("waiting");

        Map<String, Object> clear = Map.of("rows", List.of(row("A", 60, 30), row("B", 60, 6)),
                "before", Map.of("A", 50, "B", 50), "minPerArm", 20, "floorPercent", 10, "threshold", 1.64);
        Decision shift = tuner.decide(req(clear));
        assertThat(shift.action()).isEqualTo("shift");
        assertThat(shift.evidence().get("after")).isEqualTo(Map.of("A", 90, "B", 10));
        assertThat(((Number) shift.evidence().get("z")).doubleValue()).isGreaterThan(1.64);

        Map<String, Object> even = Map.of("rows", List.of(row("A", 60, 12), row("B", 60, 11)),
                "before", Map.of("A", 50, "B", 50), "minPerArm", 20, "floorPercent", 10, "threshold", 1.64);
        assertThat(tuner.decide(req(even)).action()).isEqualTo("hold");
    }

    @Test
    void outcomeJoinsBackByIdAndIgnoresRowsFromBeforeTheLog() {
        points.outcome("d-1", "conversion", 349);
        points.outcome(null, "conversion", 349);
        verify(events, Mockito.times(1)).publish(eq("DecisionOutcomeEvent"), eq("decisionOutcome"), Mockito.any());
    }

    private static Map<String, Object> row(String name, long enrolled, long converted) {
        return Map.of("name", name, "enrolled", enrolled, "converted", converted,
                "rate", Math.round(1000.0 * converted / enrolled) / 10.0);
    }

    private static DecisionRequest req(Map<String, Object> ctx) {
        return new DecisionRequest(DecisionPoints.JOURNEY_ARM_WEIGHTS, "journey", "j", ctx, List.of("shift", "hold", "waiting"));
    }
}
