package com.bss.policy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The personalization answer: the first matching rule's banner copy and
 * its experience payload (the operator's own document). No match is an
 * empty object — "no opinion", the channel's coded default applies.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"ruleId", "ruleName", "banner", "experience"})
public record ExperienceView(String ruleId, String ruleName, String banner, JsonNode experience) {

    public static final ExperienceView NONE = new ExperienceView(null, null, null, null);
}
