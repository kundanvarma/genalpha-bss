package com.bss.intelligence.incident;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One investigation as the desk and TMF724 read it: the hypothesis, its
 * confidence, where it came from, and the human verdict on it. */
@JsonPropertyOrder({"id", "signature", "processFlowId", "productOrderId", "hypothesis", "confidence",
        "proposedAction", "source", "ticketId", "verdict", "verdictNote", "diagnoseMs", "createdAt", "@type"})
public record IncidentTraceView(
        String id,
        String signature,
        String processFlowId,
        String productOrderId,
        String hypothesis,
        BigDecimal confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) String proposedAction,
        String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) String ticketId,
        String verdict,
        @JsonInclude(JsonInclude.Include.NON_NULL) String verdictNote,
        Long diagnoseMs,
        OffsetDateTime createdAt,
        @JsonProperty("@type") String type) {

    static IncidentTraceView of(IncidentTrace t) {
        return new IncidentTraceView(t.getId(), t.getSignature(), t.getProcessFlowId(),
                t.getProductOrderId(), t.getHypothesis(), t.getConfidence(), t.getProposedAction(),
                t.getSource(), t.getTicketId(), t.getVerdict(), t.getVerdictNote(), t.getDiagnoseMs(),
                t.getCreatedAt(), "IncidentTrace");
    }
}
