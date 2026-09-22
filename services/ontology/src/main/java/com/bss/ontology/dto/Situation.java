package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One thing that is the case for a customer right now: an incident on their
 * line, a paused line, an arranged or disputed or overdue amount, an open bill.
 * Each kind carries only the keys it has words for.
 */
@JsonPropertyOrder({"kind", "id", "name", "amount", "currency", "dueAt", "step", "says", "since", "severity", "actionRequired"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Situation(String kind, String id, String name, String amount, String currency, String dueAt, Integer step, String says,
        String since, String severity, boolean actionRequired) {

    public static Situation incident(String id, String says, String since) {
        return new Situation("incident", id, null, null, null, null, null, says, since, "warning", false);
    }

    public static Situation paused(String id, String name, String says) {
        return new Situation("paused", id, name, null, null, null, null, says, null, "warning", true);
    }

    public static Situation arranged(String id, String amount, String currency, String dueAt, String says) {
        return new Situation("arranged", id, null, amount, currency, dueAt, null, says, null, "info", false);
    }

    public static Situation disputed(String id, String amount, String currency, String says) {
        return new Situation("disputed", id, null, amount, currency, null, null, says, null, "info", false);
    }

    public static Situation overdue(String id, String amount, String currency, int step, String says) {
        return new Situation("overdue", id, null, amount, currency, null, step, says, null, "critical", true);
    }

    public static Situation bill(String id, String amount, String says) {
        return new Situation("bill", id, null, amount, null, null, null, says, null, "info", false);
    }
}
