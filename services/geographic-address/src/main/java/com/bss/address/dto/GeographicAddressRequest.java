package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A posted address. Only the six fields the normaliser reads are declared —
 * the map path read exactly these by name and ignored the rest, so nothing
 * on the wire changes and a body can no longer carry a column it never had.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeographicAddressRequest(
        String street1,
        String street2,
        String postCode,
        String city,
        String stateOrProvince,
        String country) {
}
