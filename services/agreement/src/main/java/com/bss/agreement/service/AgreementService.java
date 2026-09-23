package com.bss.agreement.service;

import com.bss.agreement.api.ApiConstants;
import com.bss.agreement.api.OffsetPageRequest;
import com.bss.agreement.api.PagedResult;
import com.bss.agreement.dto.AgreementPatch;
import com.bss.agreement.dto.AgreementPeriod;
import com.bss.agreement.dto.AgreementRequest;
import com.bss.agreement.dto.AgreementView;
import com.bss.agreement.entity.Agreement;
import com.bss.agreement.events.DomainEventPublisher;
import com.bss.agreement.exception.BadRequestException;
import com.bss.agreement.exception.NotFoundException;
import com.bss.agreement.repository.AgreementRepository;
import com.bss.agreement.security.PartyScope;
import com.bss.agreement.security.TenantScope;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
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
    /** Declaration order, not {@code Set.of}'s: this table is printed in the
     * refusal, and a hash-ordered set re-shuffles the sentence every JVM. */
    private static final Set<String> STATUSES = new LinkedHashSet<>(List.of(
            Agreement.IN_PROCESS, Agreement.ACTIVE, Agreement.TERMINATED));

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
    public AgreementView create(AgreementRequest dto) {
        if (dto.name() == null) {
            throw new BadRequestException("name is required");
        }
        // TMF651: the agreement's type is mandatory — `type` (spec name)
        // and `agreementType` (fleet name) are the same fact
        String agreementType = dto.resolvedType();
        if (agreementType == null) {
            throw new BadRequestException("type is required — an agreement without a"
                    + " type is a promise nobody can classify");
        }
        // v3 kits and partners say engagedPartyRole; the fleet says
        // engagedParty — same list of {id, role} references
        JsonNode parties = dto.resolvedParties();
        requireNamedCharacteristics(dto.characteristic());
        requirePermittedPartnershipRoles(agreementType, dto.characteristic(), parties);
        String owner = null;
        if (parties != null && parties.isArray()) {
            for (JsonNode ref : parties) {
                JsonNode role = ref.get("role");
                if (ref.isObject() && role != null && "customer".equalsIgnoreCase(role.asText())
                        && AgreementRequest.present(ref.get("id"))) {
                    owner = ref.get("id").asText();
                }
            }
        }
        Agreement entity = new Agreement();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/agreement/" + id);
        entity.setName(dto.name());
        entity.setAgreementType(agreementType);
        entity.setStatus(dto.status() == null ? Agreement.IN_PROCESS : requireStatus(dto.status()));
        entity.setOwnerPartyId(owner);
        JsonNode period = dto.agreementPeriod();
        if (period != null && period.isObject()) {
            entity.setPeriodStart(parseTime(period.get("startDateTime")));
            entity.setPeriodEnd(parseTime(period.get("endDateTime")));
        }
        if (Agreement.ACTIVE.equals(entity.getStatus()) && entity.getPeriodStart() == null) {
            // Created directly as active (e.g. by order completion): the
            // commitment window opens now.
            entity.setPeriodStart(OffsetDateTime.now());
        }
        // a real JSON number only: the map path's `instanceof Number` ignored "12"
        if (dto.commitmentMonths() != null && dto.commitmentMonths().isNumber()) {
            int months = dto.commitmentMonths().intValue();
            entity.setCommitmentMonths(months);
            if (entity.getPeriodStart() != null && entity.getPeriodEnd() == null) {
                entity.setPeriodEnd(entity.getPeriodStart().plusMonths(months));
            }
        }
        entity.setEngagedPartyJson(writeJson(parties));
        entity.setAgreementItemJson(writeJson(dto.agreementItem()));
        entity.setCharacteristicJson(writeJson(dto.characteristic()));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        AgreementView created = toView(repository.save(entity));
        events.publish("AgreementCreateEvent", "agreement", created);
        return created;
    }

    @Transactional(readOnly = true)
    public AgreementView findById(String id) {
        Agreement entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toView(entity);
    }

    @Transactional(readOnly = true)
    public PagedResult<AgreementView> findAll(int offset, int limit, Map<String, String> filters) {
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
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(),
                page.getTotalElements());
    }

    /** Back-office lifecycle: activate (period starts) or terminate. */
    @Transactional
    public AgreementView patch(String id, AgreementPatch patch) {
        Agreement entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.status() != null) {
            String target = requireStatus(patch.status());
            entity.setStatus(target);
            if (Agreement.ACTIVE.equals(target) && entity.getPeriodStart() == null) {
                entity.setPeriodStart(OffsetDateTime.now());
                if (entity.getCommitmentMonths() != null) {
                    entity.setPeriodEnd(entity.getPeriodStart().plusMonths(entity.getCommitmentMonths()));
                }
            }
        }
        entity.setLastUpdate(OffsetDateTime.now());
        AgreementView updated = toView(repository.save(entity));
        events.publish("AgreementStateChangeEvent", "agreement", updated);
        return updated;
    }

    /**
     * A characteristic is a NAMED value. List form: every entry with a value
     * needs a name. Map form: keyed entries (partnershipTypeId, sla…) are
     * names by construction, but a bare {value: …} names nothing.
     */
    private void requireNamedCharacteristics(JsonNode characteristic) {
        if (characteristic == null) {
            return;
        }
        if (characteristic.isArray()) {
            for (JsonNode entry : characteristic) {
                JsonNode name = entry.get("name");
                if (entry.isObject() && entry.has("value")
                        && (!AgreementRequest.present(name) || name.asText().isBlank())) {
                    throw new BadRequestException(
                            "every characteristic needs a name — a value alone names nothing");
                }
            }
        } else if (characteristic.isObject() && characteristic.has("value")
                && !AgreementRequest.present(characteristic.get("name"))
                && characteristic.size() == 1) {
            throw new BadRequestException(
                    "every characteristic needs a name — a value alone names nothing");
        }
    }

    private String requireStatus(String value) {
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

    private OffsetDateTime parseTime(JsonNode value) {
        return AgreementRequest.present(value) ? OffsetDateTime.parse(value.asText()) : null;
    }

    private AgreementView toView(Agreement a) {
        JsonNode engaged = readJson(a.getEngagedPartyJson());
        JsonNode items = readJson(a.getAgreementItemJson());
        return AgreementView.of(a.getId(), a.getHref(), a.getName(), a.getAgreementType(),
                a.getStatus(), AgreementPeriod.of(a.getPeriodStart(), a.getPeriodEnd()),
                a.getCommitmentMonths(),
                engaged == null ? objectMapper.createArrayNode() : engaged,
                items == null ? objectMapper.createArrayNode() : items,
                a.getCharacteristicJson() == null ? null : readJson(a.getCharacteristicJson()),
                a.getLastUpdate());
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BadRequestException("unserializable JSON value");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
    }

    /**
     * TMF668: a TYPED partnership is validated at signature — every engaged
     * role must be one its partnership type permits. Untyped agreements pass
     * untouched: no ceremony where none is due.
     */
    private void requirePermittedPartnershipRoles(String agreementType, JsonNode characteristic,
            JsonNode parties) {
        if (!"partnership".equalsIgnoreCase(agreementType)) {
            return;
        }
        String typeId = null;
        if (characteristic != null && characteristic.isObject()
                && AgreementRequest.present(characteristic.get("partnershipTypeId"))) {
            typeId = characteristic.get("partnershipTypeId").asText();
        }
        if (typeId == null) {
            return; // an untyped partnership is legal — the type is the opt-in
        }
        List<String> permitted = partnershipTypes.permittedRoles(typeId);
        if (permitted.isEmpty()) {
            throw new BadRequestException(
                    "partnership type '" + typeId + "' is unknown or retired");
        }
        if (parties != null && parties.isArray()) {
            for (JsonNode ref : parties) {
                if (ref.isObject() && AgreementRequest.present(ref.get("role"))) {
                    String role = ref.get("role").asText();
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
