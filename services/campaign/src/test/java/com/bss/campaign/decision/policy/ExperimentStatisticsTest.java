package com.bss.campaign.decision.policy;

import com.bss.campaign.decision.Decision;
import com.bss.campaign.decision.DecisionPoints;
import com.bss.campaign.decision.DecisionRequest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The experiment statistics, proved by running experiments. Every test here
 * first shows the OLD rule failing on the same data — a gate nobody has seen
 * fail is not a gate — and then the rule that replaced it holding.
 */
class ExperimentStatisticsTest {

    private static final int RUNS = 2000;
    private static final int PER_ARM = 1000;
    private static final int LOOK_EVERY = 10;
    private static final int MIN_PER_ARM = 20;

    /** The rule this replaced: one-sided two-proportion z of the better arm over the other, at 1.64. */
    private static boolean oldRuleCrowns(long n1, long c1, long n2, long c2) {
        double p1 = (double) c1 / n1, p2 = (double) c2 / n2, p = (double) (c1 + c2) / (n1 + n2);
        double se = Math.sqrt(p * (1 - p) * (1.0 / n1 + 1.0 / n2));
        return se != 0 && Math.abs(p1 - p2) / se >= 1.64;
    }

    private static boolean newRuleCrowns(long n1, long c1, long n2, long c2) {
        return AlwaysValid.compare(n1, c1, n2, c2, AlwaysValid.DEFAULT_EFFECT_SD, AlwaysValid.DEFAULT_ALPHA)
                .odds() >= 1 / AlwaysValid.DEFAULT_ALPHA;
    }

    private interface Rule {
        boolean crowns(long n1, long c1, long n2, long c2);
    }

    /** The share of experiments in which the rule, read every LOOK_EVERY enrolments, EVER crowned an arm. */
    private static double everCrowned(double rateA, double rateB, Rule rule, long seed) {
        Random random = new Random(seed);
        int crowned = 0;
        for (int run = 0; run < RUNS; run++) {
            long cA = 0, cB = 0;
            for (int n = 1; n <= PER_ARM; n++) {
                cA += random.nextDouble() < rateA ? 1 : 0;
                cB += random.nextDouble() < rateB ? 1 : 0;
                if (n >= MIN_PER_ARM && n % LOOK_EVERY == 0 && rule.crowns(n, cA, n, cB)) {
                    crowned++;
                    break;
                }
            }
        }
        return (double) crowned / RUNS;
    }

    @Test
    void twoIdenticalArmsReadOnAClock_theOldRuleCrownsLuck_theNewOneDoesNot() {
        // both arms convert at 10 %: there is nothing to find, so every winner is luck
        double old = everCrowned(0.10, 0.10, ExperimentStatisticsTest::oldRuleCrowns, 42);
        double now = everCrowned(0.10, 0.10, ExperimentStatisticsTest::newRuleCrowns, 42);
        System.out.println("MEASURED luck crowned, two identical arms read 99 times: old rule "
                + old + ", always-valid rule " + now);
        assertThat(old).as("the fixed z read at every look promised 5-10 %% and delivers this").isGreaterThan(0.30);
        assertThat(now).as("the always-valid rule keeps its promise at every look").isLessThanOrEqualTo(0.05);
    }

    @Test
    void theSameHoldsAtAHighConversionRateAndAtALowOne() {
        double high = everCrowned(0.50, 0.50, ExperimentStatisticsTest::newRuleCrowns, 7);
        double low = everCrowned(0.02, 0.02, ExperimentStatisticsTest::newRuleCrowns, 8);
        System.out.println("MEASURED luck crowned by the always-valid rule: at 50 % " + high + ", at 2 % " + low);
        assertThat(high).isLessThanOrEqualTo(0.05);
        assertThat(low).isLessThanOrEqualTo(0.05);
    }

    @Test
    void aRealDifferenceIsStillFound() {
        // 10 % against 18 %: honesty must not cost the ability to see a winner
        double found = everCrowned(0.18, 0.10, ExperimentStatisticsTest::newRuleCrowns, 11);
        System.out.println("MEASURED real difference (18 % vs 10 %) found within 1000 per arm: " + found);
        assertThat(found).isGreaterThan(0.90);
    }

    @Test
    void theIntervalHoldsTheTruthAtEveryLook() {
        Random random = new Random(5);
        double truth = 0.16 - 0.10;
        int escaped = 0;
        for (int run = 0; run < RUNS; run++) {
            long cA = 0, cB = 0;
            for (int n = 1; n <= PER_ARM; n++) {
                cA += random.nextDouble() < 0.16 ? 1 : 0;
                cB += random.nextDouble() < 0.10 ? 1 : 0;
                if (n >= MIN_PER_ARM && n % LOOK_EVERY == 0) {
                    AlwaysValid.Read read = AlwaysValid.compare(n, cA, n, cB,
                            AlwaysValid.DEFAULT_EFFECT_SD, AlwaysValid.DEFAULT_ALPHA);
                    if (truth < read.low() || truth > read.high()) {
                        escaped++;
                        break;
                    }
                }
            }
        }
        System.out.println("MEASURED runs where the truth ever left the interval: " + (double) escaped / RUNS);
        assertThat((double) escaped / RUNS).isLessThanOrEqualTo(0.05);
    }

    @Test
    void tunerWaitsHoldsAndShifts_andEveryExtraArmRaisesTheBar() {
        AlwaysValidTunerPolicy tuner = new AlwaysValidTunerPolicy();
        Map<String, Integer> even = Map.of("A", 50, "B", 50);
        assertThat(tuner.decide(req(List.of(row("A", 5, 2), row("B", 30, 3)), even)).action()).isEqualTo("waiting");

        Decision shift = tuner.decide(req(List.of(row("A", 60, 30), row("B", 60, 6)), even));
        assertThat(shift.action()).isEqualTo("shift");
        assertThat(shift.evidence().get("after")).isEqualTo(Map.of("A", 90, "B", 10));
        assertThat(((Number) shift.evidence().get("odds")).doubleValue()).isGreaterThan(20);
        assertThat(shift.evidence().get("bar")).isEqualTo(20.0);
        assertThat(((Number) shift.evidence().get("low")).doubleValue()).isGreaterThan(0);
        assertThat(shift.reason()).contains("holds however often we look");

        assertThat(tuner.decide(req(List.of(row("A", 60, 12), row("B", 60, 11)), even)).action()).isEqualTo("hold");

        // 22 of 60 against 9 of 60 clears z 1.64 with room to spare — the old rule shifted here.
        // Read on a clock, that is not evidence yet.
        assertThat(oldRuleCrowns(60, 22, 60, 9)).isTrue();
        assertThat(tuner.decide(req(List.of(row("A", 60, 22), row("B", 60, 9)), even)).action()).isEqualTo("hold");

        // three arms: three pairs could have been "best against runner-up", so the bar is 60 to 1
        Decision three = tuner.decide(req(List.of(row("A", 60, 30), row("B", 60, 6), row("C", 60, 5)),
                Map.of("A", 34, "B", 33, "C", 33)));
        assertThat(three.evidence().get("bar")).isEqualTo(60.0);
    }

    @Test
    void theOldDrawCutEveryExperimentAlongTheSameLines_theNewOneDoesNot() {
        // 5 000 customers, two experiments, a hundred buckets each. If the two splits are
        // independent, the customers scatter over the 10 000 (bucket, bucket) cells; if one
        // split is a shifted copy of the other, a bucket here pins the bucket there.
        Set<Integer> oldCells = new HashSet<>(), newCells = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String party = String.format("party-%06d", i);
            oldCells.add(Math.floorMod(("journey-one" + party).hashCode(), 100) * 100
                    + Math.floorMod(("journey-two" + party).hashCode(), 100));
            newCells.add((int) (HoldoutThenWeightedHashPolicy.draw("journey-one", "holdout", party) * 100) * 100
                    + (int) (HoldoutThenWeightedHashPolicy.draw("journey-two", "holdout", party) * 100));
        }
        System.out.println("MEASURED (bucket, bucket) cells used by 5000 customers across two experiments: old draw "
                + oldCells.size() + ", new draw " + newCells.size() + " of 10000");
        assertThat(oldCells.size()).as("String.hashCode: the second split is the first one, shifted").isLessThan(400);
        assertThat(newCells.size()).as("independent splits fill about 3 900 of the cells").isGreaterThan(3500);
    }

    @Test
    void theDrawIsEvenAndTheHoldoutDrawSaysNothingAboutTheArmDraw() {
        HoldoutThenWeightedHashPolicy policy = new HoldoutThenWeightedHashPolicy();
        int n = 20000, holdout = 0, a = 0, lowBoth = 0;
        for (int i = 0; i < n; i++) {
            String party = "p-" + i;
            Decision d = policy.decide(new DecisionRequest(DecisionPoints.JOURNEY_ENROLMENT, "party", party,
                    Map.of("seed", "j9", "partyId", party, "holdoutPercent", 20, "weights", Map.of("A", 70, "B", 30)),
                    List.of("holdout", "A", "B")));
            holdout += "holdout".equals(d.action()) ? 1 : 0;
            a += "A".equals(d.action()) ? 1 : 0;
            lowBoth += HoldoutThenWeightedHashPolicy.draw("j9", "holdout", party) < 0.5
                    && HoldoutThenWeightedHashPolicy.draw("j9", "arm", party) < 0.5 ? 1 : 0;
        }
        assertThat((double) holdout / n).isBetween(0.19, 0.21);
        assertThat((double) a / (n - holdout)).isBetween(0.685, 0.715);
        assertThat((double) lowBoth / n).isBetween(0.24, 0.26);
        // same customer, same experiment: the same answer every time
        assertThat(HoldoutThenWeightedHashPolicy.draw("j9", "arm", "p-1"))
                .isEqualTo(HoldoutThenWeightedHashPolicy.draw("j9", "arm", "p-1"));
        // the parts are kept apart: ("ab", "c") is not ("a", "bc")
        assertThat(HoldoutThenWeightedHashPolicy.draw("ab", "x", "c"))
                .isNotEqualTo(HoldoutThenWeightedHashPolicy.draw("a", "x", "bc"));
    }

    @Test
    void aCampaignsUnweightedArmsAreAnEvenThirdEach() {
        HoldoutThenWeightedHashPolicy policy = new HoldoutThenWeightedHashPolicy();
        for (int i = 0; i < 50; i++) {
            String party = "p-" + i;
            Decision d = policy.decide(new DecisionRequest(DecisionPoints.CAMPAIGN_TREATMENT, "party", party,
                    Map.of("seed", "c1", "partyId", party, "holdoutPercent", 10), List.of("holdout", "A", "B", "C")));
            assertThat(d.propensity()).isEqualTo("holdout".equals(d.action()) ? 0.1 : 0.3);
        }
    }

    private static Map<String, Object> row(String name, long enrolled, long converted) {
        return Map.of("name", name, "enrolled", enrolled, "converted", converted,
                "rate", Math.round(1000.0 * converted / enrolled) / 10.0);
    }

    private static DecisionRequest req(List<Map<String, Object>> rows, Map<String, Integer> before) {
        return new DecisionRequest(DecisionPoints.JOURNEY_ARM_WEIGHTS, "journey", "j",
                Map.of("rows", rows, "before", before, "minPerArm", 20, "floorPercent", 10),
                List.of("shift", "hold", "waiting"));
    }
}
