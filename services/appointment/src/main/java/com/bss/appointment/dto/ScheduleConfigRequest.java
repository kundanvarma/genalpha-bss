package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The calendar as an operator edits it: a sparse document where a key that is
 * absent means "leave it alone". Every component is an open node because the
 * old map read each one differently — {@code timezone} through
 * {@code String.valueOf} (so an explicit null asked for the zone "null" and was
 * refused), {@code provider} through a null check (so an explicit null meant
 * "back to the roster"). A record cannot carry a field this service never
 * declared; the nodes keep the leniency the wire already had.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleConfigRequest(JsonNode timezone, JsonNode workingDays, JsonNode slotStarts,
                                    JsonNode slotHours, JsonNode daysAhead, JsonNode defaultCapacity,
                                    JsonNode provider, JsonNode providerUrl,
                                    JsonNode providerSecretRef, JsonNode providerCategory) {
}
