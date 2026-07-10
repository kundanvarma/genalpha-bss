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
import com.bss.appointment.security.PartyScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF646: installer visits. Slots are two-hour windows on business days,
 * SLOT_CAPACITY bookings each — searchTimeSlot lists what is still free, and
 * creating an appointment into a full slot is a 409, checked transactionally.
 */
@Service
public class AppointmentService {

    static final int SLOT_CAPACITY = 3;
    private static final int DAYS_AHEAD = 7;
    private static final List<LocalTime> SLOT_STARTS = List.of(
            LocalTime.of(9, 0), LocalTime.of(11, 0), LocalTime.of(13, 0), LocalTime.of(15, 0));
    private static final int SLOT_HOURS = 2;

    private static final String RESOURCE = "Appointment";

    private final AppointmentRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper;

    public AppointmentService(AppointmentRepository repository, DomainEventPublisher events,
            PartyScope partyScope, ObjectMapper objectMapper) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.objectMapper = objectMapper;
    }

    /** Free slots over the next week: business days, minus fully booked ones. */
    @Transactional(readOnly = true)
    public Map<String, Object> searchTimeSlot() {
        List<Map<String, Object>> free = new ArrayList<>();
        LocalDate day = LocalDate.now().plusDays(1);
        for (int d = 0; d < DAYS_AHEAD; d++, day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (LocalTime startTime : SLOT_STARTS) {
                OffsetDateTime start = day.atTime(startTime).atOffset(ZoneOffset.UTC);
                if (repository.confirmedAt(start) < SLOT_CAPACITY) {
                    free.add(Map.of("validFor", Map.of(
                            "startDateTime", start.toString(),
                            "endDateTime", start.plusHours(SLOT_HOURS).toString())));
                }
            }
        }
        return Map.of(
                "id", UUID.randomUUID().toString(),
                "@type", "SearchTimeSlot",
                "status", "done",
                "availableTimeSlot", free);
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, String relatedPartyId) {
        Appointment probe = new Appointment();
        if (relatedPartyId != null) {
            probe.setOwnerPartyId(relatedPartyId);
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Appointment> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        Appointment entity = repository.findById(id)
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
        if (repository.confirmedAt(start) >= SLOT_CAPACITY) {
            throw new ConflictException("time slot " + start + " is fully booked");
        }

        Appointment entity = new Appointment();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/appointment/" + id);
        entity.setStatus(Appointment.CONFIRMED);
        entity.setDescription(dto.get("description") == null ? null : String.valueOf(dto.get("description")));
        entity.setStartAt(start);
        entity.setEndAt(end);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setRelatedEntityJson(writeJson(dto.get("relatedEntity")));
        entity.setPlaceJson(writeJson(dto.get("place")));
        entity.setCreationDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("AppointmentCreateEvent", "appointment", created);
        return created;
    }

    /** The one legal change: cancelling a confirmed appointment (frees its slot). */
    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Appointment entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!Appointment.CANCELLED.equals(patch.get("status"))) {
            throw new BadRequestException("the only supported change is status: 'cancelled'");
        }
        if (!Appointment.CONFIRMED.equals(entity.getStatus())) {
            throw new ConflictException("appointment is '" + entity.getStatus() + "' and cannot be cancelled");
        }
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
                "startDateTime", entity.getStartAt().toString(),
                "endDateTime", entity.getEndAt().toString()));
        if (entity.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of(
                    "id", entity.getOwnerPartyId(), "role", "customer", "@referredType", "Individual")));
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
