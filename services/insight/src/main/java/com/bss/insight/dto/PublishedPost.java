package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What the platform said about the post it just took: its id and where it lives. */
@JsonPropertyOrder({"id", "permalink"})
public record PublishedPost(String id, String permalink) {
}
