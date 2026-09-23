package com.bss.ticket.privacy;

import com.bss.ticket.entity.TroubleTicket;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One shelf of the privacy passport: every ticket this service holds for a
 * person, as stored. The rows are the entities themselves — the right of
 * access answers with what is kept, not with the API's view of it.
 */
@JsonPropertyOrder({"category", "count", "items"})
public record PrivacyExport(String category, int count, List<TroubleTicket> items) {

    public static PrivacyExport of(String category, List<TroubleTicket> items) {
        return new PrivacyExport(category, items.size(), items);
    }
}
