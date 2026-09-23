package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * The TMF673 validation answer. The submitted block is the caller's own
 * document, echoed back exactly as posted — unknown keys and all — so it
 * stays an open map behind the typed envelope.
 */
@JsonPropertyOrder({"id", "@type", "submittedGeographicAddress", "validationResult",
        "standardizedGeographicAddress", "registryMatch", "validationReason"})
public record AddressValidationResult(
        String id,
        @JsonProperty("@type") String type,
        Map<String, Object> submittedGeographicAddress,
        String validationResult,
        @JsonInclude(JsonInclude.Include.NON_NULL) StandardizedAddress standardizedGeographicAddress,
        @JsonInclude(JsonInclude.Include.NON_NULL) RegistryMatch registryMatch,
        @JsonInclude(JsonInclude.Include.NON_NULL) String validationReason) {

    public static AddressValidationResult success(String id, Map<String, Object> submitted,
            StandardizedAddress standardized) {
        return new AddressValidationResult(id, "GeographicAddressValidation", submitted,
                "success", standardized, null, null);
    }

    public static AddressValidationResult failed(String id, Map<String, Object> submitted,
            String reason) {
        return new AddressValidationResult(id, "GeographicAddressValidation", submitted,
                "failed", null, null, reason);
    }

    public AddressValidationResult withRegistryMatch(RegistryMatch match) {
        return new AddressValidationResult(id, type, submittedGeographicAddress, validationResult,
                standardizedGeographicAddress, match, validationReason);
    }

    /** A question the service asks itself, not a wire key. */
    @JsonIgnore
    public boolean isSuccess() {
        return "success".equals(validationResult);
    }
}
