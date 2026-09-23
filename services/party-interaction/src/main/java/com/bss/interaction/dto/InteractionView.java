package com.bss.interaction.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An interaction on the wire (TMF683): the document the caller posted, with
 * the server's own facts overlaid. What the service derives is typed —
 * identifiers, the clocks, the organisation, the mandatory channel/direction/
 * reason trio; what the caller wrote and the service only stores (an
 * interactionItem, a subject, a house field) rides in {@code extensions} and
 * round-trips untouched.
 *
 * <p>Blocks that may be a caller's document stay open nodes: a channel is
 * legally an array of references but a legacy row posted a bare string and an
 * odd one posted a number, and an explicit null the caller posted is written
 * back as null — {@code NON_NULL} here means "the key was never there", not
 * "the value is empty".
 *
 * <p>Declared keys are written first and the stored extras after. The map this
 * replaced kept the posted key order with the overrides in place, so that is
 * the one key-order change this record owns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "href", "description", "channel", "reason", "direction", "status",
        "sourceSystem", "relatedParty", "organization", "interactionDate", "lastUpdate", "@type"})
public record InteractionView(
        String id,
        String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode description,
        JsonNode channel,
        JsonNode reason,
        String direction,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode sourceSystem,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode relatedParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) OrgRef organization,
        OffsetDateTime interactionDate,
        OffsetDateTime lastUpdate,
        /** Everything the caller posted that this record does not declare, in the order posted. */
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public InteractionView {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    @JsonProperty("@type")
    public String atType() {
        return "PartyInteraction";
    }
}
