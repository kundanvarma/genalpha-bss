package com.bss.address.dto;

import com.bss.address.entity.GeographicAddress;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The address a site stands at, embedded so "where?" costs no second call. */
@JsonPropertyOrder({"id", "street1", "street2", "postCode", "city", "stateOrProvince",
        "country", "@referredType"})
public record SitePlace(
        String id,
        String street1,
        @JsonInclude(JsonInclude.Include.NON_NULL) String street2,
        String postCode,
        String city,
        @JsonInclude(JsonInclude.Include.NON_NULL) String stateOrProvince,
        String country,
        @JsonProperty("@referredType") String referredType) {

    public static SitePlace of(GeographicAddress a) {
        return new SitePlace(a.getId(), a.getStreet1(), a.getStreet2(), a.getPostCode(),
                a.getCity(), a.getStateOrProvince(), a.getCountry(), "GeographicAddress");
    }
}
