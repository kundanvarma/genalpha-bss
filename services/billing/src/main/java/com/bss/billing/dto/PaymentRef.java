package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TMF PaymentRef on a bill: the payment that settled it. Stored as the
 * client sent it, so anything beyond the known keys rides in {@code extensions}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "@referredType"})
public record PaymentRef(String id, String href, String name,
        @JsonProperty("@referredType") String referredType,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public PaymentRef {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    public static PaymentRef of(String id) {
        return new PaymentRef(id, null, null, "Payment", null);
    }
}
