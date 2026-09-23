package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The one legal change: the receiver marking their message read. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessagePatch(@JsonProperty("status") String status) {
}
