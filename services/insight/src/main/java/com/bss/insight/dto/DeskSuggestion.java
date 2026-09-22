package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A suggestion with its evidence sentence and, beside it, the structured facts
 * a desk can say in its own words (form, fields, count, features...) — and, where
 * the fix is safe, a one-click {@link SuggestedAction}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "kind", "target", "title", "evidence", "audience", "action", "quiet", "decisionId",
        "form", "fields", "stopField", "count", "features", "opened", "actions", "people"})
public record DeskSuggestion(String id, String kind, String target, String title, String evidence, String audience,
        SuggestedAction action, boolean quiet, String decisionId, String form, List<String> fields, String stopField,
        Integer count, List<String> features, Integer opened, Integer actions, Integer people) {

    public DeskSuggestion preset(String form, List<String> fields, int count) {
        return new DeskSuggestion(id, kind, target, title, evidence, audience, action, quiet, decisionId, form, fields, null,
                count, null, null, null, null);
    }

    public DeskSuggestion abandon(String form, String stopField, int count) {
        return new DeskSuggestion(id, kind, target, title, evidence, audience, action, quiet, decisionId, form, null,
                stopField, count, null, null, null, null);
    }

    public DeskSuggestion rewrite(String form, int count) {
        return new DeskSuggestion(id, kind, target, title, evidence, audience, action, quiet, decisionId, form, null, null,
                count, null, null, null, null);
    }

    public DeskSuggestion unused(List<String> features, int opened, int actions, int people) {
        return new DeskSuggestion(id, kind, target, title, evidence, audience, action, quiet, decisionId, null, null, null,
                null, features, opened, actions, people);
    }
}
