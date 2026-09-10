package com.bss.campaign.decision;

/**
 * The registry entry for one DecisionPoint: which policy answers it today and
 * how much autonomy the point has. {@code high} = reversible UX/message
 * choices, {@code medium} = recommendations and traffic shifts, {@code low} =
 * money, rights or statute (none of those live in this service).
 */
public record DecisionPointSpec(
        String name,
        String subjectType,
        String autonomy,
        String description,
        DecisionPolicy policy) {
}
