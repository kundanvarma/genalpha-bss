package com.bss.intelligence.workforce;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One item of the open queue, derived live from a backlog (ticket, legacy
 * incident, unapplied cash). The id is stable and derived: kind~subjectRef. */
@JsonPropertyOrder({"id", "kind", "subjectRef", "summary"})
public record OpenTask(String id, String kind, String subjectRef, String summary) {

    static OpenTask of(String kind, String subjectRef, String summary) {
        return new OpenTask(kind + "~" + subjectRef, kind, subjectRef,
                summary.length() > 490 ? summary.substring(0, 490) : summary);
    }
}
