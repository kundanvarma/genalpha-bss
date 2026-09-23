package com.bss.interaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Who the interaction was with. The service derives this pair — the customer
 * it tracked and, where one handled the contact, the agent — whenever it knows
 * the customer; a CTK-created row keeps whatever it posted instead.
 *
 * <p>Key order is the one the wire already has (the customer's
 * {@code @referredType} before its {@code role}); it came from a salted
 * {@code Map.of}, so it is pinned to what the old image printed. The agent
 * reference carries no {@code @referredType} and {@code NON_NULL} leaves it
 * off, which is the two-key shape the agent row has always had.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "@referredType", "role"})
public record PartyRef(
        String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("@referredType") String referredType,
        String role) {

    public static PartyRef customer(String partyId) {
        return new PartyRef(partyId, "Individual", "customer");
    }

    public static PartyRef agent(String agentId) {
        return new PartyRef(agentId, null, "agent");
    }
}
