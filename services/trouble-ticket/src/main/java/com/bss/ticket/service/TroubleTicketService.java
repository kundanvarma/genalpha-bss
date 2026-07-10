package com.bss.ticket.service;

import com.bss.ticket.api.ApiConstants;
import com.bss.ticket.api.OffsetPageRequest;
import com.bss.ticket.api.PagedResult;
import com.bss.ticket.entity.TroubleTicket;
import com.bss.ticket.events.DomainEventPublisher;
import com.bss.ticket.exception.BadRequestException;
import com.bss.ticket.exception.ConflictException;
import com.bss.ticket.exception.NotFoundException;
import com.bss.ticket.repository.TroubleTicketRepository;
import com.bss.ticket.security.OrgScope;
import com.bss.ticket.security.PartyScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF621 with the channel model built in: a customer creates and follows
 * their own tickets (party scope); the owning organisation's agents work them
 * (org scope) — a partner's agents never see the operator's queue.
 */
@Service
public class TroubleTicketService {

    private static final String RESOURCE = "TroubleTicket";
    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
    };
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            TroubleTicket.ACKNOWLEDGED, Set.of(TroubleTicket.IN_PROGRESS, TroubleTicket.RESOLVED),
            TroubleTicket.IN_PROGRESS, Set.of(TroubleTicket.RESOLVED),
            TroubleTicket.RESOLVED, Set.of(TroubleTicket.CLOSED, TroubleTicket.IN_PROGRESS));

    private final TroubleTicketRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final OrgScope orgScope;
    private final ObjectMapper objectMapper;
    private final String defaultOrg;

    public TroubleTicketService(TroubleTicketRepository repository, DomainEventPublisher events,
            PartyScope partyScope, OrgScope orgScope, ObjectMapper objectMapper,
            @Value("${bss.org.default-org:genalpha-retail}") String defaultOrg) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.orgScope = orgScope;
        this.objectMapper = objectMapper;
        this.defaultOrg = defaultOrg;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        TroubleTicket probe = new TroubleTicket();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "severity" -> probe.setSeverity(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        orgScope.scopedOrgId().ifPresent(probe::setOrgId);
        Page<TroubleTicket> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        TroubleTicket entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireVisible(entity);
        return toMap(entity);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || String.valueOf(dto.get("name")).isBlank()) {
            throw new BadRequestException("name is required");
        }
        TroubleTicket entity = new TroubleTicket();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/troubleTicket/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setDescription(dto.get("description") == null ? null : String.valueOf(dto.get("description")));
        entity.setSeverity(dto.get("severity") == null ? "minor" : String.valueOf(dto.get("severity")));
        entity.setStatus(TroubleTicket.ACKNOWLEDGED);
        // Customer tickets belong to the customer and the operator's default
        // org; agent-raised tickets belong to the named customer and the
        // agent's own org.
        String customerParty = partyScope.scopedPartyId().orElseGet(() -> customerIn(dto));
        entity.setOwnerPartyId(customerParty);
        entity.setOrgId(orgScope.scopedOrgId().orElse(defaultOrg));
        entity.setRelatedEntityJson(writeJson(dto.get("relatedEntity")));
        entity.setNoteJson(writeJson(normalizeNotes(dto.get("note"))));
        entity.setCreationDate(OffsetDateTime.now());
        entity.setStatusChangeDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("TroubleTicketCreateEvent", "troubleTicket", created);
        return created;
    }

    /**
     * Two legal changes: a status transition along the lifecycle, and
     * appending notes. Customers may only append notes and close a resolved
     * ticket; agents and back-office drive the rest.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        TroubleTicket entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireVisible(entity);

        Object newStatus = patch.get("status");
        if (newStatus != null && !String.valueOf(newStatus).equals(entity.getStatus())) {
            String target = String.valueOf(newStatus);
            if (partyScope.scopedPartyId().isPresent() && !TroubleTicket.CLOSED.equals(target)) {
                throw new BadRequestException("customers may only close a resolved ticket");
            }
            Set<String> allowed = TRANSITIONS.getOrDefault(entity.getStatus(), Set.of());
            if (!allowed.contains(target)) {
                throw new ConflictException("ticket is '" + entity.getStatus()
                        + "' and cannot become '" + target + "'");
            }
            entity.setStatus(target);
            entity.setStatusChangeDate(OffsetDateTime.now());
        }
        if (patch.get("note") != null) {
            List<Map<String, Object>> notes = readJsonArray(entity.getNoteJson());
            notes.addAll((List<Map<String, Object>>) normalizeNotes(patch.get("note")));
            entity.setNoteJson(writeJson(notes));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(repository.save(entity));
        events.publish("TroubleTicketStateChangeEvent", "troubleTicket", updated);
        return updated;
    }

    /** Notes get their author and timestamp stamped server-side. */
    @SuppressWarnings("unchecked")
    private Object normalizeNotes(Object note) {
        if (note == null) {
            return new ArrayList<Map<String, Object>>();
        }
        String author = SecurityContextHolder.getContext().getAuthentication() == null ? "system"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> n : (List<Map<String, Object>>) note) {
            normalized.add(Map.of(
                    "text", String.valueOf(n.getOrDefault("text", "")),
                    "author", author,
                    "date", OffsetDateTime.now().toString()));
        }
        return normalized;
    }

    private String customerIn(Map<String, Object> dto) {
        if (dto.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }

    /** Customers: own tickets only. Agents: own org only. Both 404, never 403. */
    private void requireVisible(TroubleTicket entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
        orgScope.scopedOrgId().ifPresent(org -> {
            if (!org.equals(entity.getOrgId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private Map<String, Object> toMap(TroubleTicket entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("name", entity.getName());
        if (entity.getDescription() != null) {
            map.put("description", entity.getDescription());
        }
        map.put("severity", entity.getSeverity());
        map.put("status", entity.getStatus());
        if (entity.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of(
                    "id", entity.getOwnerPartyId(), "role", "customer", "@referredType", "Individual")));
        }
        map.put("organization", Map.of("id", entity.getOrgId(), "@referredType", "Organization"));
        Object related = readJson(entity.getRelatedEntityJson());
        if (related != null) {
            map.put("relatedEntity", related);
        }
        map.put("note", readJsonArray(entity.getNoteJson()));
        map.put("creationDate", entity.getCreationDate());
        map.put("statusChangeDate", entity.getStatusChangeDate());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "TroubleTicket");
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

    private List<Map<String, Object>> readJsonArray(String json) {
        try {
            return json == null ? new ArrayList<>() : objectMapper.readValue(json, JSON_ARRAY);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
