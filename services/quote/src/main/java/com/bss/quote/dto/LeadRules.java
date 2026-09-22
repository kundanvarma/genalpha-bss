package com.bss.quote.dto;

import com.bss.quote.entity.LeadRoutingRule;
import com.bss.quote.entity.LeadScoringRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The tenant's lead scoring and routing rules. */
public final class LeadRules {

    private LeadRules() {
    }

    /** Points for a fact about the lead: its source, a company, a size, a keyword, CDP engagement. */
    @JsonPropertyOrder({"id", "field", "value", "points"})
    public record ScoringRuleView(String id, String field,
            @JsonInclude(JsonInclude.Include.NON_NULL) String value, int points) {

        public static ScoringRuleView of(LeadScoringRule r) {
            return new ScoringRuleView(r.getId(), r.getField(), r.getValue(), r.getPoints());
        }
    }

    /** From this score up, the lead goes to this rep. */
    @JsonPropertyOrder({"id", "minScore", "assignee"})
    public record RoutingRuleView(String id, int minScore, String assignee) {

        public static RoutingRuleView of(LeadRoutingRule r) {
            return new RoutingRuleView(r.getId(), r.getMinScore(), r.getAssignee());
        }
    }
}
