package com.bss.ordering.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Puts a credit decision in front of a person (#422): a TMF621 trouble ticket
 * in the care queue, carrying the order and the reason. The answer is the
 * ticket's id. Failing is loud on purpose — a customer who asked for a person
 * must never be told it is filed when it is not.
 */
public interface ReviewTicketClient {

    /** Files the ticket and returns its id; throws when the ticket service did not take it. */
    String open(ReviewTicket ticket);

    /** The TMF621 create body ordering sends — typed, so its shape is pinned. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"name", "description", "ticketType", "severity", "note", "relatedParty", "relatedEntity"})
    record ReviewTicket(String name, String description, String ticketType, String severity,
            List<Note> note, List<PartyRef> relatedParty, List<EntityRef> relatedEntity) {
    }

    record Note(String text) {
    }

    @JsonPropertyOrder({"id", "role", "@referredType"})
    record PartyRef(String id, String role, @JsonProperty("@referredType") String referredType) {
    }

    @JsonPropertyOrder({"id", "href", "name", "role", "@referredType"})
    record EntityRef(String id, String href, String name, String role,
            @JsonProperty("@referredType") String referredType) {
    }
}
