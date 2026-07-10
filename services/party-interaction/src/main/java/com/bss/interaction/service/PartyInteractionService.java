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
    private final String defaultOrg;

    public PartyInteractionService(PartyInteractionRepository repository, DomainEventPublisher events,
            PartyScope partyScope, OrgScope orgScope,
            @Value("${bss.org.default-org:genalpha-retail}") String defaultOrg) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.orgScope = orgScope;
        this.defaultOrg = defaultOrg;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        PartyInteraction probe = new PartyInteraction();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "channel" -> probe.setChannel(f.getValue());
                case "relatedPartyId" -> probe.setCustomerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setCustomerPartyId);
        orgScope.scopedOrgId().ifPresent(probe::setOrgId);
        Page<PartyInteraction> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        PartyInteraction entity = repository.findById(id)
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
        if (dto.get("description") == null || String.valueOf(dto.get("description")).isBlank()) {
            throw new BadRequestException("description is required");
        }
        String customer = customerIn(dto);
        if (customer == null) {
            throw new BadRequestException("relatedParty with role 'customer' is required");
        }
        PartyInteraction entity = new PartyInteraction();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/partyInteraction/" + id);
        entity.setDescription(String.valueOf(dto.get("description")));
        entity.setChannel(dto.get("channel") == null ? "phone" : String.valueOf(dto.get("channel")));
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

    private Map<String, Object> toMap(PartyInteraction entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("description", entity.getDescription());
        map.put("channel", entity.getChannel());
        map.put("direction", entity.getDirection());
        map.put("status", entity.getStatus());
        map.put("relatedParty", entity.getAgentId() == null
                ? List.of(Map.of("id", entity.getCustomerPartyId(), "role", "customer", "@referredType", "Individual"))
                : List.of(
                    Map.of("id", entity.getCustomerPartyId(), "role", "customer", "@referredType", "Individual"),
                    Map.of("id", entity.getAgentId(), "role", "agent")));
        map.put("organization", Map.of("id", entity.getOrgId(), "@referredType", "Organization"));
        map.put("interactionDate", entity.getInteractionDate());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "PartyInteraction");
        return map;
    }
}
