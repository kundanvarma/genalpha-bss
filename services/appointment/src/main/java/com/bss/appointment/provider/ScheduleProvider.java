package com.bss.appointment.provider;

import com.bss.appointment.schedule.ScheduleConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * THE FIELD-SERVICE SEAM. Everything the shop, CSR and fulfilment know about
 * installer availability goes through TMF646 (searchTimeSlot, appointment);
 * this interface is what answers it. Two implementations ship: the built-in
 * roster, and a TMF646 client that delegates to the tenant's own workforce
 * management system (TM Forum's TMFC046 component exposes exactly this API;
 * ServiceNow FSM for Telecom speaks it natively; Oracle Field Service and
 * Salesforce Field Service get a named adapter of the same shape).
 */
public interface ScheduleProvider {

    /** A window the provider can offer: when, and how many more visits it still holds. */
    record Window(OffsetDateTime start, OffsetDateTime end, long remaining) { }

    /**
     * What the caller knows: where, for what, for whom, and (optionally) the windows they asked
     * about. These four blocks are the caller's own TMF646 documents — the house calendar reads
     * the windows, the TMF646 adapter forwards them all untouched — so they stay open nodes on
     * the seam, and both implementations share exactly this projection.
     */
    record SlotRequest(String tenantId, JsonNode relatedPlace, JsonNode relatedEntity,
                       JsonNode relatedParty, JsonNode requestedTimeSlot) { }

    /** A booking to place: the window, plus the TMF646 context that travels with it. */
    record BookingRequest(String tenantId, OffsetDateTime start, OffsetDateTime end, String description,
                          JsonNode relatedPlace, JsonNode relatedEntity, String partyId) { }

    /** What a placed booking is known as outside (null when the provider keeps no external id). */
    record Booking(String externalId) { }

    /** Reachability probe: never books, never mutates. */
    record Probe(boolean ok, String detail) { }

    String key();

    List<Window> search(ScheduleConfig cfg, SlotRequest request);

    Booking book(ScheduleConfig cfg, BookingRequest request);

    void cancel(ScheduleConfig cfg, String externalId);

    Probe probe(ScheduleConfig cfg);
}
