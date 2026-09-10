package com.bss.campaign.decision;

import java.util.List;

/**
 * The effective Learning Contract the seam applies to one decision: the
 * machine-readable half of intent. {@code allowedActions} null = every
 * candidate; {@code explorationMaxPercent} null = no cap; {@code autonomy}
 * and {@code fallbackAction} null = the point's defaults; {@code enabled}
 * false = the policy is paused and the fallback answers every time.
 */
public record Contract(
        String id,
        int version,
        String objective,
        List<String> secondaryMetrics,
        List<String> guardrails,
        List<String> allowedActions,
        Integer explorationMaxPercent,
        String autonomy,
        String fallbackAction,
        boolean enabled) {

    /** "id@version" — what a decision record cites. */
    public String ref() {
        return id + "@" + version;
    }
}
