package com.bss.intelligence.risk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF related-party reference as the risk face reads and writes it. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role"})
public record PartyRef(String id, @JsonInclude(JsonInclude.Include.NON_NULL) String role) {
}
