package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A definition in words: an action, an agent, a concept, a console page or a
 * whole journey. {@code known} is the page's alone (does the ontology have an
 * entry for it); {@code steps} the journey's alone.
 */
@JsonPropertyOrder({"kind", "name", "title", "known", "text", "lines", "steps"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Explanation(String kind, String name, String title, Boolean known, String text, List<String> lines, List<Step> steps) {

    /** One station of a journey: concept, precondition, permission, policy, execute, effect, event, receipt. */
    @JsonPropertyOrder({"kind", "name", "says"})
    public record Step(String kind, String name, String says) {
    }

    public static Explanation of(String kind, String name, String title, List<String> lines, String separator) {
        return new Explanation(kind, name, title, null, String.join(separator, lines), lines, null);
    }

    public Explanation withSteps(List<Step> journey) {
        return new Explanation(kind, name, title, known, text, lines, journey);
    }
}
