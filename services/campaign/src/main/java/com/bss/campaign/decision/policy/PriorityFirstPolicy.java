package com.bss.campaign.decision.policy;

import com.bss.campaign.decision.Decision;
import com.bss.campaign.decision.DecisionPolicy;
import com.bss.campaign.decision.DecisionRequest;

import java.util.Map;

/**
 * Next-best-action arbitration as it has always worked: the journey with the
 * higher priority speaks, ties go to the one that reached the customer first
 * (the first candidate). Context: {@code priorities} (journeyId → priority).
 * Deterministic, so no propensity.
 */
public class PriorityFirstPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "priority-first";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Decision decide(DecisionRequest r) {
        Map<?, ?> priorities = r.ctx("priorities") instanceof Map<?, ?> m ? m : Map.of();
        String best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (String candidate : r.eligibleActions()) {
            int p = priorities.get(candidate) instanceof Number n ? n.intValue() : 0;
            if (best == null || p > bestPriority) {
                best = candidate;
                bestPriority = p;
            }
        }
        return Decision.deterministic(best, "highest priority (" + bestPriority + ") is the next-best-action this moment",
                Map.of("priorities", priorities));
    }
}
