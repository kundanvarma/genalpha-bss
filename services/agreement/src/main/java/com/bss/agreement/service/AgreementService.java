package com.bss.agreement.service;

import com.bss.agreement.api.ApiConstants;
import com.bss.agreement.api.OffsetPageRequest;
import com.bss.agreement.api.PagedResult;
import com.bss.agreement.entity.Agreement;
import com.bss.agreement.events.DomainEventPublisher;
import com.bss.agreement.exception.BadRequestException;
import com.bss.agreement.exception.NotFoundException;
import com.bss.agreement.repository.AgreementRepository;
import com.bss.agreement.security.PartyScope;
import com.bss.agreement.security.TenantScope;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF651: the customer's terms. An agreement records what was committed —
 * which products, for whom, from when to when — typically minted by order
 * completion for offerings that carry a commitment term. Customers read
 * their own agreements (404-not-403 beyond that); writes are back-office
 * and machine work.
 */
@Service
public class AgreementService {

    private static final String RESOURCE = "Agreement";
    private static final Set<String> STATUSES = Set.of(
            Agreement.IN_PROCESS, Agreement.ACTIVE, Agreement.TERMINATED);

    private final AgreementRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    private final PartnershipTypeService partnershipTypes;

    public AgreementService(AgreementRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, ObjectMapper objectMapper,
            PartnershipTypeService partnershipTypes) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.partnershipTypes = partnershipTypes;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null) {
            throw new BadRequestException("name is required");
        }
        // TMF651: the agreement's type is mandatory — `type` (spec name)
        // and `agreementType` (fleet name) are the same fact
        if (dto.get("agreementType") == null && dto.get("type") != null) {
            dto.put("agreementType", dto.get("type"));
        }
        if (dto.get("agreementType") == null) {
            throw new BadRequestException("type is required — an agreement without a"
                    + " type is a promise nobody can classify");
        }
        // v3 kits and partners say engagedPartyRole; the fleet says
        // engagedParty — same list of {id, role} references
        if (dto.get("engagedParty") == null && dto.get("engagedPartyRole") instanceof List<?>) {
            dto.put("engagedParty", dto.get("engagedPartyRole"));
        }
        requireNamedCharacteristics(dto.get("characteristic"));
        requirePermittedPartnershipRoles(dto);
        String owner = null;
        if (dto.get("engagedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))
                        && ref.get("id") != null) {
                    owner = String.valueOf(ref.get("id"));
                }
            }
        }
        Agreement entity = new Agreement();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/agreement/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setAgreementType(dto.get("agreementType") == null ? "commercial"
                : String.valueOf(dto.get("agreementType")));
        entity.setStatus(dto.get("status") == null ? Agreement.IN_PROCESS : requireStatus(dto.get("status")));
        entity.setOwnerPartyId(owner);
        if (dto.get("agreementPeriod") instanceof Map<?, ?> period) {
            entity.setPeriodStart(parseTime(period.get("startDateTime")));
            entity.setPeriodEnd(parseTime(period.get("endDateTime")));
        }
        if (Agreement.ACTIVE.equals(entity.getStatus()) && entity.getPeriodStart() == null) {
            // Created directly as active (e.g. by order completion): the
            // commitment window opens now.
            entity.setPeriodStart(OffsetDateTime.now());
        }
        if (dto.get("commitmentMonths") instanceof Number months) {
            entity.setCommitmentMonths(months.intValue());
            if (entity.getPeriodStart() != null && entity.getPeriodEnd() == null) {
                entity.setPeriodEnd(entity.getPeriodStart().plusMonths(months.intValue()));
            }
        }
        entity.setEngagedPartyJson(writeJson(dto.get("engagedParty")));
        entity.setAgreementItemJson(writeJson(dto.get("agreementItem")));
        entity.setCharacteristicJson(writeJson(dto.get("characteristic")));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("AgreementCreateEvent", "agreement", created);
        return created;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        Agreement entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toMap(entity);
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        Agreement probe = new Agreement();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(requireStatus(f.getValue()));
                case "type", "agreementType" -> probe.setAgreementType(f.getValue());
                case "relatedPartyId", "engagedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        // newest first: a fixed page of an aging list must still show what
        // was just signed (the proof run's pagination lesson)
        Page<Agreement> page = repository.findAll(Example.of(probe),
                new OffsetPageRequest(offset, limit,
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    /** Back-office lifecycle: activate (period starts) or terminate. */
    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Agreement entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.get("status") != null) {
            String target = requireStatus(patch.get("status"));
            entity.setStatus(target);
            if (Agreement.ACTIVE.equals(target) && entity.getPeriodStart() == null) {
                entity.setPeriodStart(OffsetDateTime.now());
                if (entity.getCommitmentMonths() != null) {
                    entity.setPeriodEnd(entity.getPeriodStart().plusMonths(entity.getCommitmentMonths()));
                }
            }
        }
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(repository.save(entity));
        events.publish("AgreementStateChangeEvent", "agreement", updated);
        return updated;
    }

    /**
     * A characteristic is a NAMED value. List form: every entry with a value
     * needs a name. Map form: keyed entries (partnershipTypeId, sla…) are
     * names by construction, but a bare {value: …} names nothing.
     */
    private void requireNamedCharacteristics(Object characteristic) {
        if (characteristic instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> m && m.containsKey("value")
                        && (m.get("name") == null || String.valueOf(m.get("name")).isBlank())) {
                    throw new BadRequestException(
                            "every characteristic needs a name — a value alone names nothing");
                }
            }
        } else if (characteristic instanceof Map<?, ?> m
                && m.containsKey("value") && m.get("name") == null && m.size() == 1) {
            throw new BadRequestException(
                    "every characteristic needs a name — a value alone names nothing");
        }
    }

    private String requireStatus(Object status) {
        String value = String.valueOf(status);
        if (!STATUSES.contains(value)) {
            throw new BadRequestException("status must be one of " + STATUSES);
        }
        return value;
    }

    private void requireOwn(Agreement entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private OffsetDateTime parseTime(Object value) {
        return value == null ? null : OffsetDateTime.parse(String.valueOf(value));
    }

    private Map<String, Object> toMap(Agreement a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        map.put("name", a.getName());
        map.put("agreementType", a.getAgreementType());
        map.put("type", a.getAgreementType());
        map.put("status", a.getStatus());
        if (a.getPeriodStart() != null || a.getPeriodEnd() != null) {
            Map<String, Object> period = new LinkedHashMap<>();
            if (a.getPeriodStart() != null) period.put("startDateTime", a.getPeriodStart().toString());
            if (a.getPeriodEnd() != null) period.put("endDateTime", a.getPeriodEnd().toString());
            map.put("agreementPeriod", period);
        }
        if (a.getCommitmentMonths() != null) map.put("commitmentMonths", a.getCommitmentMonths());
        Object engaged = readJson(a.getEngagedPartyJson());
        Object items = readJson(a.getAgreementItemJson());
        map.put("engagedParty", engaged == null ? List.of() : engaged);
        map.put("engagedPartyRole", engaged == null ? List.of() : engaged);
        map.put("agreementItem", items == null ? List.of() : items);
        if (a.getCharacteristicJson() != null) map.put("characteristic", readJson(a.getCharacteristicJson()));
        map.put("lastUpdate", a.getLastUpdate());
        map.put("@type", "Agreement");
        return map;
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BadRequestException("unserializable JSON value");
        }
    }

    private Object readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
    }

    /**
     * TMF668: a TYPED partnership is validated at signature — every engaged
     * role must be one its partnership type permits. Untyped agreements pass
     * untouched: no ceremony where none is due.
     */
    @SuppressWarnings("unchecked")
    private void requirePermittedPartnershipRoles(Map<String, Object> dto) {
        if (!"partnership".equalsIgnoreCase(String.valueOf(dto.get("agreementType")))) {
            return;
        }
        String typeId = null;
        Object characteristic = dto.get("characteristic");
        if (characteristic instanceof Map<?, ?> m && m.get("partnershipTypeId") != null) {
            typeId = String.valueOf(m.get("partnershipTypeId"));
        }
        if (typeId == null) {
            return; // an untyped partnership is legal — the type is the opt-in
        }
        List<String> permitted = partnershipTypes.permittedRoles(typeId);
        if (permitted.isEmpty()) {
            throw new BadRequestException(
                    "partnership type '" + typeId + "' is unknown or retired");
        }
        if (dto.get("engagedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("role") != null) {
                    String role = String.valueOf(ref.get("role"));
                    if (permitted.stream().noneMatch(r -> r.equalsIgnoreCase(role))) {
                        throw new BadRequestException("role '" + role
                                + "' is not permitted by this partnership type (permitted: "
                                + String.join(", ", permitted) + ")");
                    }
                }
            }
        }
    }

}
