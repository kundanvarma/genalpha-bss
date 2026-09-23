package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Preview the copy a template renders: the caller's context stays open. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RenderPreviewRequest(
        @JsonProperty("locale") String locale,
        @JsonProperty("context") Map<String, Object> context) {
}
