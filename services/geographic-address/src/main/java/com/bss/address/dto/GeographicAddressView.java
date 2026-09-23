package com.bss.address.dto;

import com.bss.address.entity.GeographicAddress;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A stored address as the API shows it; a column that is null is left off. */
@JsonPropertyOrder({"id", "href", "street1", "street2", "postCode", "city",
        "stateOrProvince", "country", "@type"})
public record GeographicAddressView(
        String id,
        String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) String street1,
        @JsonInclude(JsonInclude.Include.NON_NULL) String street2,
        @JsonInclude(JsonInclude.Include.NON_NULL) String postCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String city,
        @JsonInclude(JsonInclude.Include.NON_NULL) String stateOrProvince,
        @JsonInclude(JsonInclude.Include.NON_NULL) String country,
        @JsonProperty("@type") String type) {

    public static GeographicAddressView of(GeographicAddress a) {
        return new GeographicAddressView(a.getId(), a.getHref(), a.getStreet1(), a.getStreet2(),
                a.getPostCode(), a.getCity(), a.getStateOrProvince(), a.getCountry(),
                "GeographicAddress");
    }
}
