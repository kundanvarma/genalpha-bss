package com.bss.campaign.decision.policy;

import com.bss.campaign.decision.Decision;
import com.bss.campaign.decision.DecisionPolicy;
import com.bss.campaign.decision.DecisionRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The split every journey and campaign uses. Context: {@code seed}
 * (journey/campaign id), {@code partyId}, {@code holdoutPercent},
 * {@code weights} (arm → percent, optional). Eligible actions: {@code holdout}
 * plus the arm names (or {@code message} when there are no arms).
 * Deterministic per party — the same customer always lands in the same bucket
 * of the same experiment. Propensity is the probability a fresh customer would
 * have been dealt this action: holdout% for holdout, (1 − holdout%) × weight
 * for an arm.
 *
 * <p>Version 2 draws from SHA-256 over (experiment, purpose, customer).
 * Version 1 took {@code String.hashCode()} of the three concatenated, which is
 * a sum: the customer's part was the same number in every experiment, only
 * shifted. Two experiments therefore cut the customer base along the same
 * lines — customers who shared a bucket in one shared a bucket in the next —
 * and the holdout draw and the arm draw were shifted copies of each other. A
 * split that is not independent of the last split is not a random split. A
 * customer is dealt once per experiment and the deal is stored, so nobody
 * dealt by version 1 moves.
 */
public class HoldoutThenWeightedHashPolicy implements DecisionPolicy {

    public static final String HOLDOUT = "holdout";

    @Override
    public String name() {
        return "holdout-then-weighted-hash";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public Decision decide(DecisionRequest r) {
        String seed = r.ctxString("seed");
        String partyId = r.ctxString("partyId");
        int holdoutPercent = r.ctxInt("holdoutPercent", 0);
        boolean holdoutEligible = r.eligibleActions().contains(HOLDOUT);
        List<String> arms = r.eligibleActions().stream().filter(a -> !HOLDOUT.equals(a)).toList();
        double treatedShare = holdoutEligible ? (100 - holdoutPercent) / 100.0 : 1.0;
        if (holdoutEligible && holdoutPercent > 0 && draw(seed, "holdout", partyId) * 100 < holdoutPercent) {
            return new Decision(HOLDOUT, holdoutPercent / 100.0,
                    "drawn into the " + holdoutPercent + " % holdout — measures lift against silence",
                    Map.of("holdoutPercent", holdoutPercent));
        }
        if (arms.isEmpty()) {
            return new Decision(HOLDOUT, 1.0, "nothing to send", Map.of());
        }
        boolean uniform = r.ctx("weights") == null;
        Map<String, Double> weights = uniform ? evenWeights(arms) : weightsOf(r, arms);
        double bucket = draw(seed, "arm", partyId) * 100;
        double acc = 0;
        String chosen = arms.get(arms.size() - 1);
        for (String a : arms) {
            acc += weights.getOrDefault(a, 0d);
            if (bucket < acc) {
                chosen = a;
                break;
            }
        }
        double propensity = treatedShare * weights.getOrDefault(chosen, 0d) / 100.0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("holdoutPercent", holdoutPercent);
        evidence.put("weights", weights);
        return new Decision(chosen, Math.round(propensity * 10000) / 10000.0,
                arms.size() == 1 ? "treated — the only variant" : "treated — dealt \"" + chosen + "\" by the current weights",
                evidence);
    }

    /**
     * A number in [0, 1) that depends on all three parts and on nothing else.
     * The parts are length-prefixed, so ("ab", "c") and ("a", "bc") differ.
     */
    static double draw(String seed, String purpose, String partyId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {String.valueOf(seed), purpose, String.valueOf(partyId)}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return (ByteBuffer.wrap(sha.digest()).getLong() >>> 11) * 0x1.0p-53;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }

    /** A campaign's A/B arms carry no weights: an exactly even split, not 34/33/33. */
    private static Map<String, Double> evenWeights(List<String> arms) {
        Map<String, Double> w = new LinkedHashMap<>();
        arms.forEach(a -> w.put(a, 100.0 / arms.size()));
        return w;
    }

    /** Weights over the ELIGIBLE arms, normalised to 100 — when a constraint removed an arm,
     *  its traffic is shared out pro rata and the propensity stays a true probability. */
    @SuppressWarnings("unchecked")
    private Map<String, Double> weightsOf(DecisionRequest r, List<String> arms) {
        Map<String, Double> w = new LinkedHashMap<>();
        int base = 100 / arms.size();
        for (int i = 0; i < arms.size(); i++) {
            w.put(arms.get(i), (double) (i == 0 ? 100 - base * (arms.size() - 1) : base));
        }
        if (r.ctx("weights") instanceof Map<?, ?> given) {
            for (Map.Entry<?, ?> en : ((Map<Object, Object>) given).entrySet()) {
                if (w.containsKey(String.valueOf(en.getKey())) && en.getValue() instanceof Number n) {
                    w.put(String.valueOf(en.getKey()), n.doubleValue());
                }
            }
        }
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0 && Math.abs(sum - 100) > 0.0001) {
            for (Map.Entry<String, Double> en : w.entrySet()) {
                en.setValue(Math.round(en.getValue() * 100 / sum * 100) / 100.0);
            }
        }
        return w;
    }
}
