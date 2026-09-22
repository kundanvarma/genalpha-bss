package com.bss.intelligence.incident;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A runbook drafted from confirmed diagnoses, with the decision on it. */
@JsonPropertyOrder({"id", "signature", "version", "status", "title", "diagnosis", "action", "provenance",
        "createdAt", "decidedAt", "decidedNote", "@type"})
public record IncidentRunbookView(
        String id,
        String signature,
        int version,
        String status,
        String title,
        String diagnosis,
        String action,
        String provenance,
        OffsetDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime decidedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String decidedNote,
        @JsonProperty("@type") String type) {

    static IncidentRunbookView of(IncidentRunbook rb) {
        return new IncidentRunbookView(rb.getId(), rb.getSignature(), rb.getVersion(), rb.getStatus(),
                rb.getTitle(), rb.getDiagnosis(), rb.getAction(), rb.getProvenanceJson(), rb.getCreatedAt(),
                rb.getDecidedAt(), rb.getDecidedAt() == null ? null : rb.getDecidedNote(), "IncidentRunbook");
    }
}
