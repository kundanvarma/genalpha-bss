package com.bss.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/**
 * A help article on the wire: what the '?' drawer, the storefront's help and
 * the console's authoring tab read. Every key is written even when its value
 * is null — a tagless, uncategorised article says so rather than going quiet,
 * exactly as the map did.
 */
@JsonPropertyOrder({"id", "href", "title", "body", "tags", "category", "audience", "status",
        "lastUpdate", "@type"})
public record ArticleView(
        String id,
        String href,
        String title,
        String body,
        String tags,
        String category,
        String audience,
        String status,
        OffsetDateTime lastUpdate) {

    @JsonProperty("@type")
    public String atType() {
        return "Article";
    }
}
