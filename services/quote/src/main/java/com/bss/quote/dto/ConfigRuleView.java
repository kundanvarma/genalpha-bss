package com.bss.quote.dto;

import com.bss.quote.entity.QuoteConfigRule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A CPQ configuration rule: requires/excludes another offering, or a min/max quantity. */
@JsonPropertyOrder({"id", "ruleType", "subjectOffering", "objectOffering", "qty", "message"})
public record ConfigRuleView(String id, String ruleType, String subjectOffering,
        @JsonInclude(JsonInclude.Include.NON_NULL) String objectOffering,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer qty,
        String message) {

    public static ConfigRuleView of(QuoteConfigRule r) {
        return new ConfigRuleView(r.getId(), r.getRuleType(), r.getSubjectOffering(), r.getObjectOffering(),
                r.getQty(), r.getMessage());
    }
}
