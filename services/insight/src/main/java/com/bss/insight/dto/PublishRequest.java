package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A post for the brand's own handle. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishRequest(String content) {
}
