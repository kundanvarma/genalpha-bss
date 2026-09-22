package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * One thing to do next for a customer: a governed action dry-run through the
 * registry ({@code kind} action, with its check), something to explain (kind
 * explain), or an offer to consider (kind offer). Ranked by the situation, then
 * moved by what this desk did with the same recommendation before; every one
 * shown is a decision with an id whose outcome the loop learns from.
 */
@JsonPropertyOrder({"kind", "action", "title", "inputs", "offeringId", "priority", "allowed", "check", "why", "meaning", "autonomy",
        "ranking", "rank", "decisionId"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Recommendation(String kind, String action, String title, Map<String, String> inputs, String offeringId, int priority,
        boolean allowed, Check check, String why, String meaning, String autonomy, Ranking ranking, Integer rank, String decisionId) {

    /** How the desk's history moved this recommendation: the counts, the adjustment, and why in words. */
    @JsonPropertyOrder({"priority", "shown", "accepted", "dismissed", "adjustment", "says"})
    public record Ranking(int priority, int shown, int accepted, int dismissed, int adjustment, String says) {
    }

    public static Recommendation explain(String id, String title, String why, int priority) {
        return new Recommendation("explain", id, title, null, null, priority, true, null, why, null, null, null, null, null);
    }

    public static Recommendation offer(String title, String offeringId, int priority, String why, String meaning) {
        return new Recommendation("offer", "considerOffer", title, Map.of("offeringId", offeringId), offeringId, priority, true, null, why,
                meaning, null, null, null, null);
    }

    public static Recommendation unregistered(String name, String title, Map<String, String> inputs, int priority, String why) {
        return new Recommendation("action", name, title, inputs, null, priority, false, null, why, null, null, null, null, null);
    }

    public static Recommendation action(String name, String title, Map<String, String> inputs, int priority, Check check, String why,
            String meaning, String autonomy) {
        return new Recommendation("action", name, title, inputs, null, priority, check.allowed(), check, why, meaning, autonomy, null, null, null);
    }

    public Recommendation ranked(Ranking how, int newRank) {
        return new Recommendation(kind, action, title, inputs, offeringId, priority, allowed, check, why, meaning, autonomy, how, newRank, decisionId);
    }

    public Recommendation decided(String id) {
        return new Recommendation(kind, action, title, inputs, offeringId, priority, allowed, check, why, meaning, autonomy, ranking, rank, id);
    }

    /** A customer with several identical products is told once: the same action for the same thing for the same reason. */
    public String dedupeKey() {
        return action + "|" + (offeringId == null ? "" : offeringId) + "|" + (why == null ? "" : why);
    }
}
