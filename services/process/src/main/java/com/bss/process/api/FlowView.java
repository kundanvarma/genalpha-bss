package com.bss.process.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One run of a flow, projected from the event stream. The correlation key
 * is named for what it correlates to — an offer's launch governance says
 * {@code productOfferingId}, everything else {@code productOrderId} — so
 * the two share one slot and exactly one of them is ever written.
 */
@JsonPropertyOrder({"id", "href", "specCode", "productOfferingId", "productOrderId", "state",
        "message", "relatedParty", "startedAt", "completedAt", "taskFlow", "summary",
        "timeline", "@type"})
public record FlowView(
        String id,
        String href,
        String specCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String productOfferingId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String productOrderId,
        String state,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PartyRef> relatedParty,
        OffsetDateTime startedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime completedAt,
        List<TaskView> taskFlow,
        Summary summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<TimelineEntry> timeline,
        @JsonProperty("@type") String type) {

    /** The customer this flow belongs to. Key order is the wire's, not the field list's. */
    @JsonPropertyOrder({"role", "id"})
    public record PartyRef(String role, String id) {
        public static PartyRef customer(String partyId) {
            return new PartyRef("customer", partyId);
        }
    }

    /** One step of the spec, as it stands right now. */
    @JsonPropertyOrder({"id", "code", "name", "state", "allowanceSeconds", "message", "@type"})
    public record TaskView(
            String id,
            String code,
            String name,
            String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long allowanceSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) String message,
            @JsonProperty("@type") String type) {

        public static TaskView of(String id, String code, String name, String state,
                Long allowanceSeconds, String message) {
            return new TaskView(id, code, name, state,
                    allowanceSeconds != null && allowanceSeconds > 0 ? allowanceSeconds : null,
                    message, "TaskFlow");
        }
    }

    /**
     * The order's status in one honest sentence — the CSR-call deflector.
     * {@code needsAttention} is the agent's flag: overdue, may need a nudge.
     */
    @JsonPropertyOrder({"headline", "why", "needsAttention", "stepsDone", "stepsTotal"})
    public record Summary(String headline, String why, boolean needsAttention,
            int stepsDone, int stepsTotal) {
    }

    /** One cross-system event on the flow's timeline, as journalled. */
    @JsonPropertyOrder({"eventTime", "eventType", "sourceTopic", "digest"})
    public record TimelineEntry(OffsetDateTime eventTime, String eventType,
            String sourceTopic, String digest) {
    }
}
