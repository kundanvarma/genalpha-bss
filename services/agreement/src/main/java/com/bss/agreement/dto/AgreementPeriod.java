package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/**
 * The commitment window. Both ends are written with the clock's own
 * {@code toString()}, as the map path wrote them — not through Jackson's
 * date handling — so the text on the wire is unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"startDateTime", "endDateTime"})
public record AgreementPeriod(String startDateTime, String endDateTime) {

    public static AgreementPeriod of(OffsetDateTime start, OffsetDateTime end) {
        return start == null && end == null ? null
                : new AgreementPeriod(start == null ? null : start.toString(),
                        end == null ? null : end.toString());
    }
}
