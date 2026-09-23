package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A related party on a service, an order, a resource or an event: the
 * customer who owns the line, the operator who runs it, the giver and the
 * receiver of a transfer. {@code href} rides only where the map wrote it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "role"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartyRef(String id, String href, String role) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, null, "customer");
    }

    /** The owning customer with its party href — the TMF638 shape. */
    public static PartyRef customerAt(String id) {
        return new PartyRef(id, "/tmf-api/party/v4/individual/" + id, "customer");
    }

    /** The operator that runs every service of a tenant. */
    public static PartyRef serviceProvider(String tenantId) {
        return new PartyRef("op-" + tenantId, "/tmf-api/party/v4/organization/op-" + tenantId, "serviceProvider");
    }

    public static PartyRef of(String id, String role) {
        return new PartyRef(id, null, role);
    }

    /** The one-customer list the events carry, or null when the row has no owner. */
    public static List<PartyRef> customerListOrNull(String ownerPartyId) {
        return ownerPartyId == null ? null : List.of(customer(ownerPartyId));
    }
}
