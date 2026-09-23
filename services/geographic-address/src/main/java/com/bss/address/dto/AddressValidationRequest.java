package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A validation ask. The submitted address is echoed back verbatim and the
 * party block is read field by field, so both stay open; the record's job
 * here is that no other field of the body reaches the service at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressValidationRequest(Object submittedGeographicAddress, Object relatedParty) {

    /** The same wash, reached from create — a pure postal validation, no party. */
    public static AddressValidationRequest of(GeographicAddressRequest address) {
        Map<String, Object> submitted = new LinkedHashMap<>();
        put(submitted, "street1", address.street1());
        put(submitted, "street2", address.street2());
        put(submitted, "postCode", address.postCode());
        put(submitted, "city", address.city());
        put(submitted, "stateOrProvince", address.stateOrProvince());
        put(submitted, "country", address.country());
        return new AddressValidationRequest(submitted, null);
    }

    private static void put(Map<String, Object> into, String key, String value) {
        if (value != null) {
            into.put(key, value);
        }
    }
}
