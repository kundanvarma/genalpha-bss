package com.bss.quote.dto;

import com.bss.quote.entity.OpportunityActivity;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;
import java.util.List;

/** The open next-step queue across the pipeline, soonest due first. */
@JsonPropertyOrder({"openCount", "tasks"})
public record OpenTasks(int openCount, List<TaskView> tasks) {

    public static OpenTasks of(List<TaskView> tasks) {
        return new OpenTasks(tasks.size(), tasks);
    }

    /** An open task: the activity's keys, then the deal it belongs to and whether it is overdue. */
    @JsonPropertyOrder({"activity", "opportunityId", "overdue"})
    public record TaskView(@JsonUnwrapped ActivityView activity, String opportunityId, boolean overdue) {

        public static TaskView of(OpportunityActivity a, OffsetDateTime now) {
            return new TaskView(ActivityView.of(a), a.getOpportunityId(),
                    a.getDueDate() != null && a.getDueDate().isBefore(now));
        }
    }
}
