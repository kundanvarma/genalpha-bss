package com.bss.interaction.service;

import com.bss.interaction.api.ApiConstants;
import com.bss.interaction.api.OffsetPageRequest;
import com.bss.interaction.api.PagedResult;
import com.bss.interaction.entity.PartyInteraction;
import com.bss.interaction.events.DomainEventPublisher;
import com.bss.interaction.exception.BadRequestException;
import com.bss.interaction.exception.NotFoundException;
import com.bss.interaction.repository.PartyInteractionRepository;
import com.bss.interaction.security.OrgScope;
import com.bss.interaction.security.PartyScope;
import com.bss.interaction.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF683: the touchpoint log. Interactions are written once — by the agent
 * who handled the contact — and read by their organisation (org scope) and
 * the customer they concern (party scope).
 */
@Service
public class PartyInteractionService {

    private static final String RESOURCE = "PartyInteraction";

    private final PartyInteractionRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final OrgScope orgScope;
    private final TenantScope tenantScope;
    private final String defaultOrg;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public PartyInteractionService(PartyInteractionRepository repository, DomainEventPublisher events,
            PartyScope partyScope, OrgScope orgScope, TenantScope tenantScope,
            @Value("${bss.org.default-org:genalpha-retail}") String defaultOrg) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.orgScope = orgScope;
        this.tenantScope = tenantScope;
        this.defaultOrg = defaultOrg;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        PartyInteraction probe = new PartyInteraction();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "href" -> probe.setHref(f.getValue());
                case "direction" -> probe.setDirection(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setCustomerPartyId(f.getValue());
                // TMF630 permits ignoring unsupported filtering (fields/sort and
                // rich attributes like reason/channel are not indexed columns).
                default -> { }
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setCustomerPartyId);
        orgScope.scopedOrgId().ifPresent(probe::setOrgId);
        Page<PartyInteraction> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        PartyInteraction entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getCustomerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        orgScope.scopedOrgId().ifPresent(org -> {
            if (!org.equals(entity.getOrgId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        return toMap(entity);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        // TMF683: description and relatedParty are optional. Keep the customer
        // link for the CSR timeline when present; store the full body so rich
        // spec fields (channel[], reason, direction) round-trip on GET.
        String customer = customerIn(dto);
        PartyInteraction entity = new PartyInteraction();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/partyInteraction/" + id);
        entity.setPayloadJson(writeJson(dto));
        entity.setDescription(dto.get("description") == null ? null : String.valueOf(dto.get("description")));
        entity.setChannel(dto.get("channel") == null ? null : String.valueOf(dto.get("channel")));
        entity.setDirection(dto.get("direction") == null ? "inbound" : String.valueOf(dto.get("direction")));
        entity.setStatus("completed");
        entity.setCustomerPartyId(customer);
        entity.setAgentId(SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getName());
        entity.setOrgId(orgScope.scopedOrgId().orElse(defaultOrg));
        entity.setInteractionDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("PartyInteractionCreateEvent", "partyInteraction", created);
        return created;
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> dto) {
        PartyInteraction entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PartyInteraction", id));
        Object stored = readJson(entity.getPayloadJson());
        Map<String, Object> merged = new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> m) {
            merged.putAll(castMap(m));
        }
        merged.putAll(dto);
        entity.setPayloadJson(writeJson(merged));
        if (dto.get("status") != null) {
            entity.setStatus(String.valueOf(dto.get("status")));
        }
        if (dto.get("direction") != null) {
            entity.setDirection(String.valueOf(dto.get("direction")));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
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

    @SuppressWarnings("unchecked")
    /**
     * The OMNICHANNEL feed: a customer message that just went out (martech
     * blast, journey step, order notification — whatever channel spoke)
     * becomes a touchpoint on the timeline, idempotent on the source event
     * id. CSRs stop asking "what have we already said to you?" — the log
     * knows, whoever said it.
     */
    @Transactional
    public void mintTouchpoint(String sourceRef, String sourceSystem, String description,
            String channel, String customerPartyId) {
        String tenant = tenantScope.currentTenantId();
        if (repository.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return;
        }
        PartyInteraction entity = new PartyInteraction();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setHref(ApiConstants.BASE_PATH + "/partyInteraction/" + id);
        entity.setDescription(description);
        entity.setChannel(channel);
        entity.setDirection("outbound");
        entity.setStatus("completed");
        entity.setCustomerPartyId(customerPartyId);
        entity.setOrgId(defaultOrg);
        entity.setSourceRef(sourceRef);
        entity.setSourceSystem(sourceSystem);
        entity.setInteractionDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        try {
            repository.save(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // concurrent duplicate delivery lost the race — fine
        }
    }

    private Map<String, Object> toMap(PartyInteraction entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        // Start from the posted body so every spec field round-trips, then
        // overlay the server-managed fields.
        Object stored = readJson(entity.getPayloadJson());
        if (stored instanceof Map<?, ?> m) {
            map.putAll((Map<String, Object>) m);
        }
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        if (entity.getDescription() != null) {
            map.put("description", entity.getDescription());
        }
        if (entity.getChannel() != null && map.get("channel") == null) {
            map.put("channel", entity.getChannel());
        }
        // TMF683 channel is an array of channel references; normalise a legacy
        // string value (app-created rows) so it round-trips as an array.
        if (map.get("channel") instanceof String s) {
            map.put("channel", List.of(Map.of("name", s)));
        }
        map.put("direction", entity.getDirection());
        map.put("status", entity.getStatus());
        if (entity.getSourceSystem() != null) {
            map.put("sourceSystem", entity.getSourceSystem());
        }
        // Server-derived relatedParty only when we tracked a customer (app path);
        // CTK-created interactions keep whatever relatedParty they posted (overlaid above).
        if (entity.getCustomerPartyId() != null) {
            List<Map<String, Object>> parties = new java.util.ArrayList<>();
            parties.add(Map.of("id", entity.getCustomerPartyId(), "role", "customer", "@referredType", "Individual"));
            if (entity.getAgentId() != null) {
                parties.add(Map.of("id", entity.getAgentId(), "role", "agent"));
            }
            map.put("relatedParty", parties);
        }
        if (entity.getOrgId() != null) {
            map.put("organization", Map.of("id", entity.getOrgId(), "@referredType", "Organization"));
        }
        map.put("interactionDate", entity.getInteractionDate());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "PartyInteraction");
        return map;
    }
}
