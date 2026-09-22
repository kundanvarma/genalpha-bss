package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * The row that ships to the number-directory partner (and is kept as the audit
 * payload). Partial exposure carries name + phone, never the address; the
 * address itself is the contact medium's open characteristic block.
 */
@JsonPropertyOrder({"partyId", "serviceRef", "name", "exposure", "phoneNumber", "address"})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectoryListing(
        String partyId,
        String serviceRef,
        String name,
        String exposure,
        String phoneNumber,
        Map<String, Object> address) {
}
