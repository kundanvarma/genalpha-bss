package com.bss.promotion.service;

import com.bss.promotion.api.ApiConstants;
import com.bss.promotion.api.OffsetPageRequest;
import com.bss.promotion.api.PagedResult;
import com.bss.promotion.entity.Promotion;
import com.bss.promotion.entity.PromotionRedemption;
import com.bss.promotion.events.DomainEventPublisher;
import com.bss.promotion.exception.BadRequestException;
import com.bss.promotion.exception.ConflictException;
import com.bss.promotion.exception.NotFoundException;
import com.bss.promotion.repository.PromotionRedemptionRepository;
import com.bss.promotion.repository.PromotionRepository;
import com.bss.promotion.security.TenantScope;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF671: campaigns as data. A promotion is a percentage off matching
 * offerings, claimed with a code. Channels VALIDATE anonymously (a shop
 * window can price the discount before checkout); order completion REDEEMS
 * (machine write), and the billing run reads redemptions to put the
 * discount on real bills. Managing promotions is back-office work.
 */
@Service
public class PromotionService {

    private static final String RESOURCE = "Promotion";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final PromotionRepository promotions;
    private final PromotionRedemptionRepository redemptions;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public PromotionService(PromotionRepository promotions, PromotionRedemptionRepository redemptions,
            DomainEventPublisher events, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.promotions = promotions;
        this.redemptions = redemptions;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("code") == null || dto.get("percentage") == null) {
            throw new BadRequestException("name, code and percentage are required");
        }
        String tenant = tenantScope.currentTenantId();
        String code = String.valueOf(dto.get("code")).trim();
        if (promotions.findByTenantIdAndCodeIgnoreCase(tenant, code).isPresent()) {
            throw new ConflictException("promotion code '" + code + "' already exists");
        }
        Promotion entity = new Promotion();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setHref(ApiConstants.BASE_PATH + "/promotion/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setDescription(dto.get("description") == null ? null : String.valueOf(dto.get("description")));
        entity.setCode(code);
        entity.setLifecycleStatus(dto.get("lifecycleStatus") == null ? Promotion.ACTIVE
                : String.valueOf(dto.get("lifecycleStatus")));
        entity.setPercentage(new BigDecimal(String.valueOf(dto.get("percentage"))));
        if (entity.getPercentage().signum() <= 0 || entity.getPercentage().doubleValue() > 100) {
            throw new BadRequestException("percentage must be between 0 and 100");
        }
        if (dto.get("durationMonths") instanceof Number months) {
            entity.setDurationMonths(months.intValue());
        }
        if (dto.get("appliesTo") instanceof List<?> offerings && !offerings.isEmpty()) {
            entity.setAppliesToJson(writeJson(offerings));
        }
        if (dto.get("validFor") instanceof Map<?, ?> valid) {
            if (valid.get("startDateTime") != null) {
                entity.setValidFrom(OffsetDateTime.parse(String.valueOf(valid.get("startDateTime"))));
            }
            if (valid.get("endDateTime") != null) {
                entity.setValidUntil(OffsetDateTime.parse(String.valueOf(valid.get("endDateTime"))));
            }
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(promotions.save(entity));
        events.publish("PromotionCreateEvent", "promotion", created);
        return created;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        Promotion probe = new Promotion();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "code" -> probe.setCode(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        Page<Promotion> page = promotions.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        Promotion entity = promotions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return toMap(entity);
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Promotion entity = promotions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.get("lifecycleStatus") != null) {
            entity.setLifecycleStatus(String.valueOf(patch.get("lifecycleStatus")));
        }
        if (patch.get("percentage") != null) {
            entity.setPercentage(new BigDecimal(String.valueOf(patch.get("percentage"))));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(promotions.save(entity));
        events.publish("PromotionAttributeValueChangeEvent", "promotion", updated);
        return updated;
    }

    /**
     * Anonymous shop-window check: is this code good, and what does it do?
     * Never enumerates promotions — you must know the code.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(Map<String, Object> request) {
        if (request.get("code") == null) {
            throw new BadRequestException("code is required");
        }
        return activeByCode(String.valueOf(request.get("code")))
                .map(p -> {
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("valid", true);
                    ok.put("name", p.getName());
                    ok.put("percentage", p.getPercentage());
                    if (p.getDurationMonths() != null) ok.put("durationMonths", p.getDurationMonths());
                    ok.put("appliesTo", readAppliesTo(p));
                    return ok;
                })
                .orElse(Map.of("valid", false));
    }

    /** Machine seam: order completion turns a code into the owner's discount. */
    @Transactional
    public Map<String, Object> redeem(Map<String, Object> request) {
        if (request.get("code") == null || request.get("relatedPartyId") == null) {
            throw new BadRequestException("code and relatedPartyId are required");
        }
        Promotion promotion = activeByCode(String.valueOf(request.get("code")))
                .orElseThrow(() -> new BadRequestException(
                        "promotion code '" + request.get("code") + "' is not valid"));
        String tenant = tenantScope.currentTenantId();
        String owner = String.valueOf(request.get("relatedPartyId"));
        if (redemptions.existsByTenantIdAndOwnerPartyIdAndPromotionId(tenant, owner, promotion.getId())) {
            throw new ConflictException("promotion already redeemed by this customer");
        }
        PromotionRedemption entity = new PromotionRedemption();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenant);
        entity.setPromotionId(promotion.getId());
        entity.setPromotionName(promotion.getName());
        entity.setCode(promotion.getCode());
        entity.setOwnerPartyId(owner);
        entity.setPercentage(promotion.getPercentage());
        entity.setAppliesToJson(promotion.getAppliesToJson());
        entity.setMonthsLeft(promotion.getDurationMonths());
        entity.setCreatedAt(OffsetDateTime.now());
        Map<String, Object> created = redemptionMap(redemptions.save(entity));
        events.publish("PromotionRedemptionCreateEvent", "promotionRedemption", created);
        return created;
    }

    /** Billing's view: the discounts a customer has earned. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> redemptionsFor(String ownerPartyId) {
        return redemptions.findByTenantIdAndOwnerPartyId(tenantScope.currentTenantId(), ownerPartyId)
                .stream().map(this::redemptionMap).toList();
    }

    private java.util.Optional<Promotion> activeByCode(String code) {
        return promotions.findByTenantIdAndCodeIgnoreCase(tenantScope.currentTenantId(), code.trim())
                .filter(p -> Promotion.ACTIVE.equals(p.getLifecycleStatus()))
                .filter(p -> p.getValidFrom() == null || !p.getValidFrom().isAfter(OffsetDateTime.now()))
                .filter(p -> p.getValidUntil() == null || !p.getValidUntil().isBefore(OffsetDateTime.now()));
    }

    private List<String> readAppliesTo(Promotion p) {
        try {
            return p.getAppliesToJson() == null ? List.of()
                    : objectMapper.readValue(p.getAppliesToJson(), STRING_LIST);
        } catch (JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
    }

    private Map<String, Object> toMap(Promotion p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("href", p.getHref());
        map.put("name", p.getName());
        if (p.getDescription() != null) map.put("description", p.getDescription());
        map.put("code", p.getCode());
        map.put("lifecycleStatus", p.getLifecycleStatus());
        map.put("percentage", p.getPercentage());
        if (p.getDurationMonths() != null) map.put("durationMonths", p.getDurationMonths());
        map.put("appliesTo", readAppliesTo(p));
        map.put("lastUpdate", p.getLastUpdate());
        map.put("@type", "Promotion");
        return map;
    }

    private Map<String, Object> redemptionMap(PromotionRedemption r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("promotionId", r.getPromotionId());
        map.put("name", r.getPromotionName());
        map.put("code", r.getCode());
        map.put("relatedPartyId", r.getOwnerPartyId());
        map.put("percentage", r.getPercentage());
        try {
            map.put("appliesTo", r.getAppliesToJson() == null ? List.of()
                    : objectMapper.readValue(r.getAppliesToJson(), STRING_LIST));
        } catch (JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
        if (r.getMonthsLeft() != null) map.put("monthsLeft", r.getMonthsLeft());
        map.put("@type", "PromotionRedemption");
        return map;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BadRequestException("unserializable JSON value");
        }
    }
}
