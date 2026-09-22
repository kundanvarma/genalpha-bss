package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The normalised mention / direct message every platform adapter returns: {id, platform, author, handle, text, created_time[, permalink]}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "platform", "author", "handle", "text", "created_time", "permalink"})
public record SocialMessage(String id, String platform, String author, String handle, String text,
        @JsonProperty("created_time") String createdTime, String permalink) {

    public String platformOr(String fallback) {
        return platform == null ? fallback : platform;
    }

    public String textOrEmpty() {
        return text == null ? "" : text;
    }
}
