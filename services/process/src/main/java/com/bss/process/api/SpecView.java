package com.bss.process.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * TMF701's design-time half: what a flow owes, and by when. The task list
 * is the operator's own authored document — it goes into the column as it
 * was posted and comes back verbatim, so it stays a list of open nodes.
 */
@JsonPropertyOrder({"code", "name", "description", "taskFlowSpecification", "@type"})
public record SpecView(
        String code,
        String name,
        String description,
        List<JsonNode> taskFlowSpecification,
        @JsonProperty("@type") String type) {

    public static SpecView of(String code, String name, String description, List<JsonNode> tasks) {
        return new SpecView(code, name, description, tasks, "ProcessFlowSpecification");
    }
}
