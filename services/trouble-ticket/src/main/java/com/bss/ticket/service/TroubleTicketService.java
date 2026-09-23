package com.bss.ticket.service;

import com.bss.ticket.api.ApiConstants;
import com.bss.ticket.api.Json;
import com.bss.ticket.api.OffsetPageRequest;
import com.bss.ticket.api.PagedResult;
import com.bss.ticket.dto.OrgRef;
import com.bss.ticket.dto.PartyRef;
import com.bss.ticket.dto.TicketNote;
import com.bss.ticket.dto.TicketView;
import com.bss.ticket.dto.TroubleTicketCreateRequest;
import com.bss.ticket.dto.TroubleTicketPatchRequest;
import com.bss.ticket.entity.TroubleTicket;
import com.bss.ticket.events.DomainEventPublisher;
import com.bss.ticket.exception.BadRequestException;
import com.bss.ticket.exception.ConflictException;
import com.bss.ticket.exception.NotFoundException;
import com.bss.ticket.repository.TroubleTicketRepository;
import com.bss.ticket.security.OrgScope;
import com.bss.ticket.security.PartyScope;
import com.bss.ticket.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private static final TypeReference<List<TicketNote>> NOTES = new TypeReference<>() {
    };
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            TroubleTicket.ACKNOWLEDGED, Set.of(TroubleTicket.IN_PROGRESS, TroubleTicket.RESOLVED),
            TroubleTicket.IN_PROGRESS, Set.of(TroubleTicket.RESOLVED),
            TroubleTicket.RESOLVED, Set.of(TroubleTicket.CLOSED, TroubleTicket.IN_PROGRESS));

    private final TroubleTicketRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final OrgScope orgScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final String defaultOrg;

    public TroubleTicketService(TroubleTicketRepository repository, DomainEventPublisher events,
            PartyScope partyScope, OrgScope orgScope, TenantScope tenantScope, ObjectMapper objectMapper,
            @Value("${bss.org.default-org:genalpha-retail}") String defaultOrg) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.orgScope = orgScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.defaultOrg = defaultOrg;
    }

    @Transactional(readOnly = true)
    public PagedResult<TicketView> findAll(int offset, int limit, Map<String, String> filters) {
        TroubleTicket probe = new TroubleTicket();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "severity" -> probe.setSeverity(f.getValue());
                case "ticketType" -> probe.setTicketType(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        orgScope.scopedOrgId().ifPresent(probe::setOrgId);
        // newest first: fresh tickets must appear on the default page even
        // when the history has thousands (the proof run's pagination lesson)
        Page<TroubleTicket> page = repository.findAll(Example.of(probe), new OffsetPageRequest(
                offset, limit, org.springframework.data.domain.Sort.by("creationDate").descending()));
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TicketView findById(String id) {
        TroubleTicket entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireVisible(entity);
        return toView(entity);
    }

    @Transactional
    public TicketView create(TroubleTicketCreateRequest dto) {
        // TMF621: description and ticketType are the spec's mandatory pair;
        // name is optional and falls back to the description
        if (!Json.present(dto.description()) && !Json.present(dto.name())) {
            throw new BadRequestException("description is required");
        }
        if (!Json.present(dto.ticketType()) || Json.valueOfLike(dto.ticketType()).isBlank()) {
            throw new BadRequestException("ticketType is required — a ticket declares its kind");
        }
        if (dto.note() != null && dto.note().isObject() && !Json.present(dto.note().get("text"))) {
            throw new BadRequestException("a note IS its text — text is required");
        }
        TroubleTicket entity = new TroubleTicket();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/troubleTicket/" + id);
        entity.setName(Json.valueOfLike(Json.present(dto.name()) ? dto.name() : dto.description()));
        entity.setDescription(Json.present(dto.description()) ? Json.valueOfLike(dto.description()) : null);
        entity.setTicketType(Json.valueOfLike(dto.ticketType()));
        entity.setSeverity(Json.present(dto.severity()) ? Json.valueOfLike(dto.severity()) : "minor");
        entity.setStatus(TroubleTicket.ACKNOWLEDGED);
        // Customer tickets belong to the customer and the operator's default
        // org; agent-raised tickets belong to the named customer and the
        // agent's own org.
        String customerParty = partyScope.scopedPartyId().orElseGet(dto::customerPartyId);
        entity.setOwnerPartyId(customerParty);
        entity.setOrgId(orgScope.scopedOrgId().orElse(defaultOrg));
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setRelatedEntityJson(Json.present(dto.relatedEntity()) ? writeJson(dto.relatedEntity()) : null);
        entity.setNoteJson(writeJson(normalizeNotes(dto.note())));
        entity.setCreationDate(OffsetDateTime.now());
        entity.setStatusChangeDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        TicketView created = toView(repository.save(entity));
        events.publish("TroubleTicketCreateEvent", "troubleTicket", created);
        return created;
    }

    /**
     * Two legal changes: a status transition along the lifecycle, and
     * appending notes. Customers may only append notes and close a resolved
     * ticket; agents and back-office drive the rest.
     */
    @Transactional
    public TicketView patch(String id, TroubleTicketPatchRequest patch) {
        TroubleTicket entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireVisible(entity);

        if (Json.present(patch.status()) && !Json.valueOfLike(patch.status()).equals(entity.getStatus())) {
            String target = Json.valueOfLike(patch.status());
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
        if (Json.present(patch.note())) {
            List<TicketNote> notes = readNotes(entity.getNoteJson());
            notes.addAll(normalizeNotes(patch.note()));
            entity.setNoteJson(writeJson(notes));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        TicketView updated = toView(repository.save(entity));
        events.publish("TroubleTicketStateChangeEvent", "troubleTicket", updated);
        return updated;
    }

    /** Notes get their author and timestamp stamped server-side. */
    private List<TicketNote> normalizeNotes(JsonNode note) {
        List<TicketNote> normalized = new ArrayList<>();
        if (!Json.present(note)) {
            return normalized;
        }
        String author = SecurityContextHolder.getContext().getAuthentication() == null ? "system"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        // A note block that is not a list of objects is the 500 the cast has
        // always answered; typing is not the moment to improve a refusal.
        for (JsonNode element : (ArrayNode) note) {
            ObjectNode n = (ObjectNode) element;
            normalized.add(new TicketNote(
                    OffsetDateTime.now().toString(),
                    author,
                    n.has("text") ? Json.valueOfLike(n.get("text")) : ""));
        }
        return normalized;
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

    private TicketView toView(TroubleTicket entity) {
        return new TicketView(
                entity.getId(),
                entity.getHref(),
                entity.getName(),
                entity.getDescription() == null ? entity.getName() : entity.getDescription(),
                entity.getSeverity(),
                entity.getTicketType() == null ? "support" : entity.getTicketType(),
                entity.getStatus(),
                entity.getOwnerPartyId() == null ? null : List.of(PartyRef.customer(entity.getOwnerPartyId())),
                OrgRef.of(entity.getOrgId()),
                readJson(entity.getRelatedEntityJson()),
                readNotes(entity.getNoteJson()),
                entity.getCreationDate(),
                entity.getStatusChangeDate(),
                entity.getLastUpdate());
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            if (json == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }

    private List<TicketNote> readNotes(String json) {
        try {
            return json == null ? new ArrayList<>() : objectMapper.readValue(json, NOTES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
