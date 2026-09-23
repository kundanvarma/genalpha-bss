package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A reachability probe of the binding's base URL — never a person lookup. */
@JsonPropertyOrder({"country", "provider", "ok", "status", "note"})
public record RegistryTestResult(
        String country,
        String provider,
        boolean ok,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer status,
        String note) {

    public static RegistryTestResult nothingToProbe(String country, String provider) {
        return new RegistryTestResult(country, provider, true, null,
                "no base URL configured — nothing to probe");
    }

    public static RegistryTestResult probed(String country, String provider, int status,
            String baseUrl) {
        return new RegistryTestResult(country, provider, status < 500, status,
                "reachability probe of " + baseUrl + "/health — never a person lookup");
    }

    public static RegistryTestResult unreachable(String country, String provider, String why) {
        return new RegistryTestResult(country, provider, false, null, "unreachable: " + why);
    }
}
