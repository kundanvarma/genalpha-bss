package com.bss.porting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The internal seam the orchestrator asks before drawing a fresh number.
 * A party with nothing ported in answers {@code {}} — the empty record, not
 * a 404: "no ported number" is an answer, not a missing resource.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"phoneNumber", "portingOrderId"})
public record PortedNumber(String phoneNumber, String portingOrderId) {

    public static final PortedNumber NONE = new PortedNumber(null, null);
}
