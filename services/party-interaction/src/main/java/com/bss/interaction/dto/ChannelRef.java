package com.bss.interaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * TMF683 makes {@code channel} an array of channel references and mandatory on
 * every interaction. House-written rows carry one channel by name — the source
 * system that spoke, or "assisted"/"digital" when only the shape of the contact
 * is known — so the whole history is conformant, not just API-created rows.
 * A row that posted its own channel keeps it verbatim; this record is only the
 * shape the service itself derives.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelRef(String name) {
}
