package com.bss.campaign.decision;

/**
 * The stand-in policy for a decision point of the operational ontology
 * (ontology.&lt;action&gt;): the registry executes what the caller chose, so there
 * is nothing to decide here — the point exists so that a learning contract can
 * state what the action is measured by and what must never happen, and so its
 * receipts sit beside the campaign ones under one vocabulary.
 */
public final class OntologyActionPolicy implements DecisionPolicy {

    @Override
    public String name() {
        return "operational-semantic-registry";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Decision decide(DecisionRequest request) {
        String first = request.eligibleActions() == null || request.eligibleActions().isEmpty()
                ? null : request.eligibleActions().get(0);
        return new Decision(first, null, "the registry executes what the caller chose; nothing is decided here",
                java.util.Map.of());
    }
}
