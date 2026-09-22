package com.bss.quote.dto;

import com.bss.quote.entity.OpportunityActivity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A beat on the deal: a call, an email, a note, a lifecycle event — or an open next-step task with a due date. */
@JsonPropertyOrder({"id", "type", "note", "occurredAt", "status", "dueDate", "assignee"})
public record ActivityView(String id, String type, String note, OffsetDateTime occurredAt, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime dueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String assignee) {

    public static ActivityView of(OpportunityActivity a) {
        return new ActivityView(a.getId(), a.getActivityType(), a.getNote(), a.getOccurredAt(), a.getStatus(),
                a.getDueDate(), a.getAssignee());
    }
}
