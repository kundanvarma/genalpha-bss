package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A reachability probe of a provider's base URL — never a money call. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"provider", "ok", "status", "note"})
public record PspTestResult(
        @JsonProperty("provider") String provider,
        @JsonProperty("ok") boolean ok,
        @JsonProperty("status") Integer status,
        @JsonProperty("note") String note) {

    public static PspTestResult reached(String provider, int status, String note) {
        return new PspTestResult(provider, status < 500, status, note);
    }

    public static PspTestResult said(String provider, boolean ok, String note) {
        return new PspTestResult(provider, ok, null, note);
    }
}
