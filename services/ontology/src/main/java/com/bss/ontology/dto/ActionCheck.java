package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The dry run of one named action: the action, then the check's own keys beside it. */
@JsonPropertyOrder({"action", "check"})
public record ActionCheck(String action, @JsonUnwrapped Check check) {
}
