package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** POST importBase: the legacy rows — one login, party and product each. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportBaseRequest(List<Row> rows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String externalRef, String givenName, String familyName, String email, String msisdn,
            String offeringName) {
    }
}
