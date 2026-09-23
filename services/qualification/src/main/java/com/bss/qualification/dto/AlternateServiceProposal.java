package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The TMF645 signature: never a bare no. When the requested technology is not
 * there, the best this address CAN have rides back beside the refusal.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "alternateService", "@type"})
public record AlternateServiceProposal(
        String id,
        ServiceView alternateService,
        @JsonProperty("@type") String type) {

    public static AlternateServiceProposal of(ServiceView service) {
        return new AlternateServiceProposal("alt-1", service, "AlternateServiceProposal");
    }
}
