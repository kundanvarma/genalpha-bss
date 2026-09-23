package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * The registry half of a validation, in its two honest shapes. A country with
 * a registry bound answers with the provider that was asked; a country with
 * none says so and names no provider — two different key orders, so two arms,
 * never one record with half its keys null.
 *
 * <p>The registered address is the registry's own document and stays open.
 * A protected or unknown person answers {@code no_data} either way: the
 * outcome is the whole answer, by design.
 */
public sealed interface RegistryMatch {

    String outcome();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"provider", "country", "outcome", "registeredAddress", "movedDate"})
    record Bound(String provider, String country, String outcome,
            Map<String, Object> registeredAddress, String movedDate) implements RegistryMatch {
    }

    @JsonPropertyOrder({"country", "outcome", "reason"})
    record Unavailable(String country, String outcome, String reason) implements RegistryMatch {

        public static Unavailable noRegistry(String country) {
            return new Unavailable(country, "unavailable", "no registry bound for " + country);
        }
    }
}
