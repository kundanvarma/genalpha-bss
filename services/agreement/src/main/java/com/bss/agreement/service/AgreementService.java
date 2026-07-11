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

    public AgreementService(AgreementRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null) {
            throw new BadRequestException("name is required");
        }
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
                case "relatedPartyId", "engagedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Agreement> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
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
        map.put("status", a.getStatus());
        if (a.getPeriodStart() != null || a.getPeriodEnd() != null) {
            Map<String, Object> period = new LinkedHashMap<>();
            if (a.getPeriodStart() != null) period.put("startDateTime", a.getPeriodStart().toString());
            if (a.getPeriodEnd() != null) period.put("endDateTime", a.getPeriodEnd().toString());
            map.put("agreementPeriod", period);
        }
        if (a.getCommitmentMonths() != null) map.put("commitmentMonths", a.getCommitmentMonths());
        map.put("engagedParty", readJson(a.getEngagedPartyJson()));
        map.put("agreementItem", readJson(a.getAgreementItemJson()));
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
}
