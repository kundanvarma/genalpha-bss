package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A reachability probe of a carrier's base URL — never a booking. One record for
 * the three honest answers (nothing to probe, reachable, unreachable): only the
 * reachable one carries an HTTP status, so that component alone is NON_NULL.
 */
@JsonPropertyOrder({"carrier", "ok", "status", "note"})
public record CarrierProbe(String carrier, boolean ok,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Integer status, String note) {

    public static CarrierProbe nothingToProbe(String carrier) {
        return new CarrierProbe(carrier, true, null, "no base URL configured — nothing to probe");
    }

    public static CarrierProbe reached(String carrier, int status, String baseUrl) {
        return new CarrierProbe(carrier, status < 500, status,
                "reachability probe of " + baseUrl + "/health — not a booking");
    }

    public static CarrierProbe unreachable(String carrier, String detail) {
        return new CarrierProbe(carrier, false, null, "unreachable: " + detail);
    }
}
