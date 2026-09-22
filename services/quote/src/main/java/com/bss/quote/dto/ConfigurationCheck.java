package com.bss.quote.dto;

import com.bss.quote.entity.QuoteConfigRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The CPQ verdict on a set of line items: valid, or the rules it breaks. */
@JsonPropertyOrder({"valid", "violations"})
public record ConfigurationCheck(boolean valid, List<RuleViolation> violations) {

    public static ConfigurationCheck of(List<RuleViolation> violations) {
        return new ConfigurationCheck(violations.isEmpty(), violations);
    }

    /** One broken rule, in the rule's own words. */
    @JsonPropertyOrder({"ruleType", "subject", "object", "message"})
    public record RuleViolation(String ruleType, String subject,
            @JsonInclude(JsonInclude.Include.NON_NULL) String object, String message) {

        public static RuleViolation of(QuoteConfigRule r) {
            return new RuleViolation(r.getRuleType(), r.getSubjectOffering(), r.getObjectOffering(), r.getMessage());
        }
    }
}
