package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One precondition of an action, judged: {@code ok} true holds, false fails, null
 * unknown (a seam that did not answer). On the wire the verdict is a word.
 */
@JsonPropertyOrder({"id", "says", "verdict", "detail"})
public record Verdict(String id, String says, @JsonIgnore Boolean ok, @JsonInclude(JsonInclude.Include.NON_NULL) String detail) {

    @JsonProperty("verdict")
    public String verdict() {
        return ok == null ? "unknown" : ok ? "holds" : "fails";
    }
}
