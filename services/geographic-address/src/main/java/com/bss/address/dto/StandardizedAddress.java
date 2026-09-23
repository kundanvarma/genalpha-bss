package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The address after normalisation: country upper-cased, postcode de-spaced,
 * city title-cased. The key order is the order the normaliser walks the
 * fields in, and a field the caller never sent is left off, as the map left
 * it off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"street1", "street2", "postCode", "city", "stateOrProvince",
        "country", "@type"})
public record StandardizedAddress(
        String street1,
        String street2,
        String postCode,
        String city,
        String stateOrProvince,
        String country,
        @JsonProperty("@type") String type) {

    public StandardizedAddress withType() {
        return new StandardizedAddress(street1, street2, postCode, city, stateOrProvince,
                country, "GeographicAddress");
    }
}
