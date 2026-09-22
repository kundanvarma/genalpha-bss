package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * TMF TimePeriod: {startDateTime, endDateTime}. Lenient on the way in — a
 * bare date ("2026-10-01") is read as midnight UTC, an unreadable bound as
 * open — exactly as the price window has always been parsed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"startDateTime", "endDateTime"})
public record TimePeriod(OffsetDateTime startDateTime, OffsetDateTime endDateTime) {

    @JsonCreator
    public static TimePeriod parse(@JsonProperty("startDateTime") Object start, @JsonProperty("endDateTime") Object end) {
        return new TimePeriod(lenient(start), lenient(end));
    }

    /** null for nothing; an ISO offset date-time as is; a date at midnight UTC. */
    public static OffsetDateTime lenient(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof OffsetDateTime t) {
            return t;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(v));
        } catch (Exception e) {
            return LocalDate.parse(String.valueOf(v).substring(0, 10)).atStartOfDay().atOffset(ZoneOffset.UTC);
        }
    }

    /** Is the instant inside the window? An absent bound is open. */
    public boolean contains(OffsetDateTime now) {
        return (startDateTime == null || !now.isBefore(startDateTime)) && (endDateTime == null || now.isBefore(endDateTime));
    }
}
