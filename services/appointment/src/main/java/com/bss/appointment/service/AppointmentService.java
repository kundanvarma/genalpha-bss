package com.bss.appointment.service;

import com.bss.appointment.api.ApiConstants;
import com.bss.appointment.api.OffsetPageRequest;
import com.bss.appointment.api.PagedResult;
import com.bss.appointment.dto.AppointmentPatch;
import com.bss.appointment.dto.AppointmentRequest;
import com.bss.appointment.dto.AppointmentView;
import com.bss.appointment.dto.PartyRef;
import com.bss.appointment.dto.SearchTimeSlotRequest;
import com.bss.appointment.dto.SearchTimeSlotResult;
import com.bss.appointment.dto.SlotView;
import com.bss.appointment.dto.TimeWindow;
import com.bss.appointment.entity.Appointment;
import com.bss.appointment.events.DomainEventPublisher;
import com.bss.appointment.exception.BadRequestException;
import com.bss.appointment.exception.ConflictException;
import com.bss.appointment.exception.NotFoundException;
import com.bss.appointment.repository.AppointmentRepository;
import com.bss.appointment.provider.ScheduleProvider;
import com.bss.appointment.provider.ScheduleProviders;
import com.bss.appointment.schedule.ScheduleConfig;
import com.bss.appointment.schedule.ScheduleService;
import com.bss.appointment.security.PartyScope;
import com.bss.appointment.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TMF646: installer visits. The tenant's ScheduleService says which windows
 * exist (timezone, working days, starts, horizon) and how many visits each
 * holds (the technician roster, or a flat default) — searchTimeSlot lists
 * what is still free, and creating an appointment into a full window is a
 * 409, checked transactionally.
 */
@Service
public class AppointmentService {

    private static final String RESOURCE = "Appointment";

    private final AppointmentRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final ScheduleService schedule;
    private final ScheduleProviders providers;

    public AppointmentService(AppointmentRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, ObjectMapper objectMapper,
            ScheduleService schedule, ScheduleProviders providers) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.schedule = schedule;
        this.providers = providers;
    }

    /**
     * TMF646 searchTimeSlot: free windows from whoever answers the tenant's
     * calendar — the built-in roster, or the tenant's own workforce system.
     * The request body is the TMF646 SearchTimeSlot (relatedPlace, relatedEntity,
     * relatedParty, requestedTimeSlot); an empty body means "anything ahead".
     */
    @Transactional(readOnly = true)
    public SearchTimeSlotResult searchTimeSlot(SearchTimeSlotRequest body) {
        SearchTimeSlotRequest dto = body == null ? SearchTimeSlotRequest.EMPTY : body;
        ScheduleConfig cfg = schedule.current();
        ScheduleProvider provider = providers.forConfig(cfg);
        ScheduleProvider.SlotRequest request = new ScheduleProvider.SlotRequest(
                cfg.getTenantId(), dto.place(), dto.entities(), dto.party(), dto.windows());
        List<SlotView> free = new ArrayList<>();
        for (ScheduleProvider.Window w : provider.search(cfg, request)) {
            free.add(new SlotView(new TimeWindow(w.start().toString(), w.end().toString()), w.remaining()));
        }
        return new SearchTimeSlotResult(
                UUID.randomUUID().toString(),
                "done",
                OffsetDateTime.now().toString(),
                free.isEmpty() ? "no availability" : "success",
                cfg.getTimezone(),
                provider.key(),
                request.relatedPlace(),
                free);
    }

    @Transactional(readOnly = true)
    public PagedResult<AppointmentView> findAll(int offset, int limit, String relatedPartyId) {
        Appointment probe = new Appointment();
        probe.setTenantId(tenantScope.currentTenantId());
        if (relatedPartyId != null) {
            probe.setOwnerPartyId(relatedPartyId);
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Appointment> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AppointmentView findById(String id) {
        Appointment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toView(entity);
    }

    @Transactional
    public AppointmentView create(AppointmentRequest body) {
        AppointmentRequest dto = body == null ? AppointmentRequest.EMPTY : body;
        JsonNode validFor = dto.window();
        if (validFor == null || !validFor.hasNonNull("startDateTime") || !validFor.hasNonNull("endDateTime")) {
            throw new BadRequestException("validFor.startDateTime and endDateTime are required");
        }
        OffsetDateTime start = OffsetDateTime.parse(validFor.get("startDateTime").asText());
        OffsetDateTime end = OffsetDateTime.parse(validFor.get("endDateTime").asText());
        if (!start.isBefore(end)) {
            throw new BadRequestException("validFor must start before it ends");
        }
        if (start.isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("appointments are booked in the future");
        }
        ScheduleConfig cfg = schedule.current();
        ScheduleProvider provider = providers.forConfig(cfg);
        String description = dto.descriptionText();
        ScheduleProvider.Booking booking = provider.book(cfg, new ScheduleProvider.BookingRequest(
                cfg.getTenantId(), start, end, description,
                dto.placeObject(), dto.entityDocument(), partyScope.scopedPartyId().orElse(null)));

        Appointment entity = new Appointment();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/appointment/" + id);
        entity.setStatus(Appointment.CONFIRMED);
        entity.setDescription(description);
        entity.setStartAt(start);
        entity.setEndAt(end);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setRelatedEntityJson(writeJson(dto.entityDocument()));
        entity.setPlaceJson(writeJson(dto.placeDocument()));
        entity.setProvider(provider.key());
        entity.setExternalId(booking.externalId());
        entity.setCreationDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        AppointmentView created = toView(repository.save(entity));
        events.publish("AppointmentCreateEvent", "appointment", created);
        return created;
    }

    /** The one legal change: cancelling a confirmed appointment (frees its slot). */
    @Transactional
    public AppointmentView patch(String id, AppointmentPatch patch) {
        Appointment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (patch == null || !patch.cancelling(Appointment.CANCELLED)) {
            throw new BadRequestException("the only supported change is status: 'cancelled'");
        }
        if (!Appointment.CONFIRMED.equals(entity.getStatus())) {
            throw new ConflictException("appointment is '" + entity.getStatus() + "' and cannot be cancelled");
        }
        // cancel where it was booked first: if the workforce system refuses, we keep it confirmed
        providers.byKey(entity.getProvider()).cancel(schedule.current(), entity.getExternalId());
        entity.setStatus(Appointment.CANCELLED);
        entity.setLastUpdate(OffsetDateTime.now());
        AppointmentView updated = toView(repository.save(entity));
        events.publish("AppointmentStateChangeEvent", "appointment", updated);
        return updated;
    }

    private void requireOwn(Appointment entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private AppointmentView toView(Appointment entity) {
        return new AppointmentView(
                entity.getId(),
                entity.getHref(),
                entity.getStatus(),
                entity.getDescription(),
                new TimeWindow(schedule.inTenantZone(entity.getStartAt()).toString(),
                        schedule.inTenantZone(entity.getEndAt()).toString()),
                entity.getOwnerPartyId() == null ? null : List.of(PartyRef.customer(entity.getOwnerPartyId())),
                entity.getExternalId(),
                entity.getProvider(),
                readJson(entity.getRelatedEntityJson()),
                readJson(entity.getPlaceJson()),
                entity.getCreationDate(),
                entity.getLastUpdate());
    }

    private String writeJson(JsonNode value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }
}
