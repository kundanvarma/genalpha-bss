package com.bss.campaign.decision.policy;

import com.bss.campaign.decision.Decision;
import com.bss.campaign.decision.DecisionPolicy;
import com.bss.campaign.decision.DecisionRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The split every journey and campaign has used since holdouts arrived, now
 * behind the seam. Context: {@code seed} (journey/campaign id), {@code partyId},
 * {@code holdoutPercent}, {@code weights} (arm → percent, optional). Eligible
 * actions: {@code holdout} plus the arm names (or {@code message} when there are
 * no arms). Deterministic per party — the same customer always lands in the
 * same bucket — with the SAME two hashes as before, so old enrolments keep
 * their bucket. Propensity is the probability a fresh customer would have
 * been dealt this action: holdout% for holdout, (1 − holdout%) × weight for an arm.
 */
public class HoldoutThenWeightedHashPolicy implements DecisionPolicy {

    public static final String HOLDOUT = "holdout";

    @Override
    public String name() {
        return "holdout-then-weighted-hash";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Decision decide(DecisionRequest r) {
        String seed = r.ctxString("seed");
        String partyId = r.ctxString("partyId");
        int holdoutPercent = r.ctxInt("holdoutPercent", 0);
        boolean holdoutEligible = r.eligibleActions().contains(HOLDOUT);
        List<String> arms = r.eligibleActions().stream().filter(a -> !HOLDOUT.equals(a)).toList();
        double treatedShare = holdoutEligible ? (100 - holdoutPercent) / 100.0 : 1.0;
        if (holdoutEligible && holdoutPercent > 0
                && Math.floorMod((seed + partyId).hashCode(), 100) < holdoutPercent) {
            return new Decision(HOLDOUT, holdoutPercent / 100.0,
                    "hashed into the " + holdoutPercent + " % holdout — measures lift against silence",
                    Map.of("holdoutPercent", holdoutPercent));
        }
        if (arms.isEmpty()) {
            return new Decision(HOLDOUT, 1.0, "nothing to send", Map.of());
        }
        Map<String, Integer> weights = weightsOf(r, arms);
        String chosen;
        if (arms.size() == 1) {
            chosen = arms.get(0);
        } else if (r.ctx("weights") == null) {
            // campaign A/B: uniform, the historical ':arm:' hash
            chosen = arms.get(Math.floorMod((seed + ":arm:" + partyId).hashCode(), arms.size()));
        } else {
            int bucket = Math.floorMod((seed + ":" + partyId + ":arm").hashCode(), 100);
            int acc = 0;
            chosen = arms.get(arms.size() - 1);
            for (String a : arms) {
                acc += weights.getOrDefault(a, 0);
                if (bucket < acc) {
                    chosen = a;
                    break;
                }
            }
        }
        double propensity = treatedShare * weights.getOrDefault(chosen, 0) / 100.0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("holdoutPercent", holdoutPercent);
        evidence.put("weights", weights);
        return new Decision(chosen, Math.round(propensity * 10000) / 10000.0,
                arms.size() == 1 ? "treated — the only variant" : "treated — dealt \"" + chosen + "\" by the current weights",
                evidence);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> weightsOf(DecisionRequest r, List<String> arms) {
        Map<String, Integer> w = new LinkedHashMap<>();
        int base = 100 / arms.size();
        for (int i = 0; i < arms.size(); i++) {
            w.put(arms.get(i), i == 0 ? 100 - base * (arms.size() - 1) : base);
        }
        if (r.ctx("weights") instanceof Map<?, ?> given) {
            for (Map.Entry<?, ?> en : ((Map<Object, Object>) given).entrySet()) {
                if (w.containsKey(String.valueOf(en.getKey())) && en.getValue() instanceof Number n) {
                    w.put(String.valueOf(en.getKey()), n.intValue());
                }
            }
        }
        return w;
    }
}
