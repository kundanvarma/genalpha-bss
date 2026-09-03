package com.bss.appointment.service;

import com.bss.appointment.api.ApiConstants;
import com.bss.appointment.api.OffsetPageRequest;
import com.bss.appointment.api.PagedResult;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchTimeSlot(Map<String, Object> body) {
        Map<String, Object> dto = body == null ? Map.of() : body;
        ScheduleConfig cfg = schedule.current();
        ScheduleProvider provider = providers.forConfig(cfg);
        ScheduleProvider.SlotRequest request = new ScheduleProvider.SlotRequest(
                cfg.getTenantId(),
                dto.get("relatedPlace") instanceof Map<?, ?> p ? (Map<String, Object>) p : null,
                dto.get("relatedEntity") instanceof List<?> e ? (List<Map<String, Object>>) e : null,
                dto.get("relatedParty") instanceof Map<?, ?> rp ? (Map<String, Object>) rp : null,
                dto.get("requestedTimeSlot") instanceof List<?> r ? (List<Map<String, Object>>) r : null);
        List<Map<String, Object>> free = new ArrayList<>();
        for (ScheduleProvider.Window w : provider.search(cfg, request)) {
            free.add(Map.of(
                    "validFor", Map.of(
                            "startDateTime", w.start().toString(),
                            "endDateTime", w.end().toString()),
                    "remaining", w.remaining()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", UUID.randomUUID().toString());
        out.put("@type", "SearchTimeSlot");
        out.put("status", "done");
        out.put("searchDate", OffsetDateTime.now().toString());
        out.put("searchResult", free.isEmpty() ? "no availability" : "success");
        out.put("timezone", cfg.getTimezone());
        out.put("provider", provider.key());
        if (request.relatedPlace() != null) {
            out.put("relatedPlace", request.relatedPlace());
        }
        out.put("availableTimeSlot", free);
        return out;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, String relatedPartyId) {
        Appointment probe = new Appointment();
        probe.setTenantId(tenantScope.currentTenantId());
        if (relatedPartyId != null) {
            probe.setOwnerPartyId(relatedPartyId);
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Appointment> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        Appointment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toMap(entity);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> dto) {
        Map<String, Object> validFor = dto.get("validFor") instanceof Map<?, ?> v
                ? (Map<String, Object>) v : null;
        if (validFor == null || validFor.get("startDateTime") == null || validFor.get("endDateTime") == null) {
            throw new BadRequestException("validFor.startDateTime and endDateTime are required");
        }
        OffsetDateTime start = OffsetDateTime.parse(String.valueOf(validFor.get("startDateTime")));
        OffsetDateTime end = OffsetDateTime.parse(String.valueOf(validFor.get("endDateTime")));
        if (!start.isBefore(end)) {
            throw new BadRequestException("validFor must start before it ends");
        }
        if (start.isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("appointments are booked in the future");
        }
        ScheduleConfig cfg = schedule.current();
        ScheduleProvider provider = providers.forConfig(cfg);
        Object place = dto.get("place") != null ? dto.get("place") : dto.get("relatedPlace");
        ScheduleProvider.Booking booking = provider.book(cfg, new ScheduleProvider.BookingRequest(
                cfg.getTenantId(), start, end,
                dto.get("description") == null ? null : String.valueOf(dto.get("description")),
                place instanceof Map<?, ?> pm ? (Map<String, Object>) pm : null,
                dto.get("relatedEntity"), partyScope.scopedPartyId().orElse(null)));

        Appointment entity = new Appointment();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/appointment/" + id);
        entity.setStatus(Appointment.CONFIRMED);
        entity.setDescription(dto.get("description") == null ? null : String.valueOf(dto.get("description")));
        entity.setStartAt(start);
        entity.setEndAt(end);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setRelatedEntityJson(writeJson(dto.get("relatedEntity")));
        entity.setPlaceJson(writeJson(place));
        entity.setProvider(provider.key());
        entity.setExternalId(booking.externalId());
        entity.setCreationDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("AppointmentCreateEvent", "appointment", created);
        return created;
    }

    /** The one legal change: cancelling a confirmed appointment (frees its slot). */
    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Appointment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!Appointment.CANCELLED.equals(patch.get("status"))) {
            throw new BadRequestException("the only supported change is status: 'cancelled'");
        }
        if (!Appointment.CONFIRMED.equals(entity.getStatus())) {
            throw new ConflictException("appointment is '" + entity.getStatus() + "' and cannot be cancelled");
        }
        // cancel where it was booked first: if the workforce system refuses, we keep it confirmed
        providers.byKey(entity.getProvider()).cancel(schedule.current(), entity.getExternalId());
        entity.setStatus(Appointment.CANCELLED);
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(repository.save(entity));
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

    private Map<String, Object> toMap(Appointment entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("status", entity.getStatus());
        if (entity.getDescription() != null) {
            map.put("description", entity.getDescription());
        }
        map.put("validFor", Map.of(
                "startDateTime", schedule.inTenantZone(entity.getStartAt()).toString(),
                "endDateTime", schedule.inTenantZone(entity.getEndAt()).toString()));
        if (entity.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of(
                    "id", entity.getOwnerPartyId(), "role", "customer", "@referredType", "Individual")));
        }
        if (entity.getExternalId() != null) {
            map.put("externalId", entity.getExternalId());
        }
        if (entity.getProvider() != null) {
            map.put("provider", entity.getProvider());
        }
        map.put("relatedEntity", readJson(entity.getRelatedEntityJson()));
        map.put("place", readJson(entity.getPlaceJson()));
        map.put("creationDate", entity.getCreationDate());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "Appointment");
        return map;
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private Object readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }
}
