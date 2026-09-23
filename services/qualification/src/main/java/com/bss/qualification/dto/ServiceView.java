package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** What the network can deliver at a place: the spec plus its measurements. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"serviceSpecification", "serviceCharacteristic", "@type"})
public record ServiceView(
        ServiceSpecificationRef serviceSpecification,
        List<Characteristic> serviceCharacteristic,
        @JsonProperty("@type") String type) {

    public static ServiceView of(String technology, List<Characteristic> characteristics) {
        return new ServiceView(ServiceSpecificationRef.of("broadband-" + technology),
                characteristics, "Service");
    }
}
