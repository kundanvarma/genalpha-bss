package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One wholesale supplier a retail ISP could buy this address from. */
@JsonPropertyOrder({"accessOwner", "accessLayer", "technology", "maxDownMbps",
        "maxUpMbps", "@type"})
public record AccessOption(
        String accessOwner,
        String accessLayer,
        String technology,
        Integer maxDownMbps,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxUpMbps,
        @JsonProperty("@type") String type) {

    public static AccessOption of(String owner, String layer, String technology,
            Integer maxDown, Integer maxUp) {
        return new AccessOption(owner, layer, technology, maxDown, maxUp,
                "WholesaleAccessOption");
    }
}
