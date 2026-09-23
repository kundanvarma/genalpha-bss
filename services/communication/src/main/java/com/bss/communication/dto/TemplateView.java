package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** Reusable, localized message copy. The locales block is the author's own
 * document — it is answered as it was stored, never re-shaped. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "channel", "locales", "promotionRef", "lastUpdate", "@type"})
public record TemplateView(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonProperty("name") String name,
        @JsonProperty("channel") String channel,
        @JsonProperty("locales") JsonNode locales,
        @JsonProperty("promotionRef") String promotionRef,
        @JsonProperty("lastUpdate") OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {
}
