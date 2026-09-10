package com.bss.campaign.decision.policy;

import com.bss.campaign.decision.Decision;
import com.bss.campaign.decision.DecisionPolicy;
import com.bss.campaign.decision.DecisionRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The auto-tuning rule (docs/journey-auto-tuning.md), moved behind the seam
 * unchanged: judge only after {@code minPerArm} treated enrolments per arm,
 * compare best with runner-up by one-sided two-proportion z, and on
 * {@code z ≥ threshold} give the best arm everything above the floor.
 * Context: {@code rows} ([{name, enrolled, converted, rate}]), {@code before}
 * (name → weight), {@code minPerArm}, {@code floorPercent}, {@code threshold}.
 * Actions: {@code shift | hold | waiting}. Evidence carries {@code after},
 * {@code z}, {@code why} — exactly what the ledger used to hold.
 */
public class ZThresholdTunerPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "z-threshold-tuner";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision decide(DecisionRequest r) {
        List<Map<String, Object>> rows = r.ctx("rows") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Map<String, Integer> before = new LinkedHashMap<>();
        if (r.ctx("before") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> before.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
        }
        int minPerArm = r.ctxInt("minPerArm", 20);
        int floor = r.ctxInt("floorPercent", 10);
        double threshold = r.ctxDouble("threshold", 1.64);
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, Integer> after = new LinkedHashMap<>(before);
        evidence.put("after", after);
        if (rows.size() < 2) {
            evidence.put("why", "fewer than two arms");
            return Decision.deterministic("hold", "fewer than two arms", evidence);
        }
        if (rows.stream().anyMatch(row -> num(row.get("enrolled")) < minPerArm)) {
            String why = "every arm needs at least " + minPerArm + " treated enrolments before it is judged";
            evidence.put("why", why);
            return Decision.deterministic("waiting", why, evidence);
        }
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Double.compare(dbl(b.get("rate")), dbl(a.get("rate"))));
        Map<String, Object> best = sorted.get(0);
        Map<String, Object> second = sorted.get(1);
        long n1 = num(best.get("enrolled")), c1 = num(best.get("converted"));
        long n2 = num(second.get("enrolled")), c2 = num(second.get("converted"));
        double p1 = (double) c1 / n1, p2 = (double) c2 / n2, p = (double) (c1 + c2) / (n1 + n2);
        double se = Math.sqrt(p * (1 - p) * (1.0 / n1 + 1.0 / n2));
        double z = se == 0 ? 0 : (p1 - p2) / se;
        double zr = Math.round(z * 100) / 100.0;
        evidence.put("z", zr);
        evidence.put("threshold", threshold);
        if (z >= threshold) {
            for (Map<String, Object> row : rows) {
                after.put(String.valueOf(row.get("name")), floor);
            }
            after.put(String.valueOf(best.get("name")), 100 - floor * (rows.size() - 1));
            String why = "\"" + best.get("name") + "\" converts at " + best.get("rate") + " % vs " + second.get("rate")
                    + " % for \"" + second.get("name") + "\" (z " + zr + " ≥ " + threshold + ")";
            evidence.put("why", why);
            return Decision.deterministic(after.equals(before) ? "hold" : "shift", why, evidence);
        }
        String why = "the difference between \"" + best.get("name") + "\" and \"" + second.get("name")
                + "\" is not evidence yet (z " + zr + " < " + threshold + ")";
        evidence.put("why", why);
        return Decision.deterministic("hold", why, evidence);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }
}
