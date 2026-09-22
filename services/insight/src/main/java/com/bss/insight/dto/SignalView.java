package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** A stored signal: redacted text, its twin, the source's context, the redaction audit — and its classification once one landed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "source", "sourceRef", "partyId", "channel", "lang", "text", "twin", "context", "redactions",
        "receivedAt", "duplicate", "@type", "classification"})
public record SignalView(String id, String source, String sourceRef, String partyId, String channel, String lang, String text,
        String twin, JsonNode context, JsonNode redactions, OffsetDateTime receivedAt, Boolean duplicate,
        @JsonProperty("@type") String type, SignalClassificationView classification) {

    public SignalView withClassification(SignalClassificationView classification) {
        return new SignalView(id, source, sourceRef, partyId, channel, lang, text, twin, context, redactions, receivedAt,
                duplicate, type, classification);
    }

    /** True when the ingest found this signal already stored (idempotent by source + ref/text). */
    public boolean duplicated() {
        return Boolean.TRUE.equals(duplicate);
    }
}
