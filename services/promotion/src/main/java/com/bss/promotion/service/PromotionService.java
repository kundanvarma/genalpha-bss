package com.bss.promotion.service;

import com.bss.promotion.api.ApiConstants;
import com.bss.promotion.api.OffsetPageRequest;
import com.bss.promotion.api.PagedResult;
import com.bss.promotion.dto.CheckPromotionRequest;
import com.bss.promotion.dto.PromotionCheck;
import com.bss.promotion.dto.PromotionPatch;
import com.bss.promotion.dto.PromotionRedemptionView;
import com.bss.promotion.dto.PromotionRequest;
import com.bss.promotion.dto.PromotionView;
import com.bss.promotion.dto.RedeemRequest;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
    public PromotionView create(PromotionRequest dto) {
        if (dto.name() == null || dto.code() == null || PromotionRequest.absent(dto.percentage())) {
            throw new BadRequestException("name, code and percentage are required");
        }
        String tenant = tenantScope.currentTenantId();
        String code = dto.code().trim();
        if (promotions.findByTenantIdAndCodeIgnoreCase(tenant, code).isPresent()) {
            throw new ConflictException("promotion code '" + code + "' already exists");
        }
        Promotion entity = new Promotion();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setHref(ApiConstants.BASE_PATH + "/promotion/" + id);
        entity.setName(dto.name());
        entity.setDescription(dto.description());
        entity.setCode(code);
        entity.setLifecycleStatus(dto.lifecycleStatus() == null ? Promotion.ACTIVE
                : dto.lifecycleStatus());
        entity.setPercentage(decimalOf(dto.percentage()));
        if (entity.getPercentage().signum() <= 0 || entity.getPercentage().doubleValue() > 100) {
            throw new BadRequestException("percentage must be between 0 and 100");
        }
        // a real JSON number only: the map path's `instanceof Number` ignored "3"
        if (dto.durationMonths() != null && dto.durationMonths().isNumber()) {
            entity.setDurationMonths(dto.durationMonths().intValue());
        }
        if (dto.appliesTo() != null && dto.appliesTo().isArray() && !dto.appliesTo().isEmpty()) {
            entity.setAppliesToJson(writeJson(dto.appliesTo()));
        }
        JsonNode valid = dto.validFor();
        if (valid != null && valid.isObject()) {
            if (!PromotionRequest.absent(valid.get("startDateTime"))) {
                entity.setValidFrom(OffsetDateTime.parse(valid.get("startDateTime").asText()));
            }
            if (!PromotionRequest.absent(valid.get("endDateTime"))) {
                entity.setValidUntil(OffsetDateTime.parse(valid.get("endDateTime").asText()));
            }
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PromotionView created = toView(promotions.save(entity));
        events.publish("PromotionCreateEvent", "promotion", created);
        return created;
    }

    /** The caller's own scale, exactly as {@code String.valueOf} handed it over. */
    private static BigDecimal decimalOf(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    @Transactional(readOnly = true)
    public PagedResult<PromotionView> findAll(int offset, int limit, Map<String, String> filters) {
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
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(),
                page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PromotionView findById(String id) {
        Promotion entity = promotions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return toView(entity);
    }

    @Transactional
    public PromotionView patch(String id, PromotionPatch patch) {
        Promotion entity = promotions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.lifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.lifecycleStatus());
        }
        if (!PromotionRequest.absent(patch.percentage())) {
            entity.setPercentage(decimalOf(patch.percentage()));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        PromotionView updated = toView(promotions.save(entity));
        events.publish("PromotionAttributeValueChangeEvent", "promotion", updated);
        return updated;
    }

    /**
     * Anonymous shop-window check: is this code good, and what does it do?
     * Never enumerates promotions — you must know the code.
     */
    @Transactional(readOnly = true)
    public PromotionCheck validate(CheckPromotionRequest request) {
        if (request.code() == null) {
            throw new BadRequestException("code is required");
        }
        return activeByCode(request.code())
                .<PromotionCheck>map(p -> PromotionCheck.Valid.of(p.getName(), p.getPercentage(),
                        p.getDurationMonths(), readAppliesTo(p)))
                .orElse(PromotionCheck.Invalid.INSTANCE);
    }

    /** Machine seam: order completion turns a code into the owner's discount. */
    @Transactional
    public PromotionRedemptionView redeem(RedeemRequest request) {
        if (request.code() == null || request.relatedPartyId() == null) {
            throw new BadRequestException("code and relatedPartyId are required");
        }
        Promotion promotion = activeByCode(request.code())
                .orElseThrow(() -> new BadRequestException(
                        "promotion code '" + request.code() + "' is not valid"));
        String tenant = tenantScope.currentTenantId();
        String owner = request.relatedPartyId();
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
        PromotionRedemptionView created = redemptionView(redemptions.save(entity));
        events.publish("PromotionRedemptionCreateEvent", "promotionRedemption", created);
        return created;
    }

    /** Billing's view: the discounts a customer has earned. */
    @Transactional(readOnly = true)
    public List<PromotionRedemptionView> redemptionsFor(String ownerPartyId) {
        return redemptions.findByTenantIdAndOwnerPartyId(tenantScope.currentTenantId(), ownerPartyId)
                .stream().map(this::redemptionView).toList();
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

    private PromotionView toView(Promotion p) {
        return PromotionView.of(p.getId(), p.getHref(), p.getName(), p.getDescription(),
                p.getCode(), p.getLifecycleStatus(), p.getPercentage(), p.getDurationMonths(),
                readAppliesTo(p), p.getLastUpdate());
    }

    private PromotionRedemptionView redemptionView(PromotionRedemption r) {
        List<String> appliesTo;
        try {
            appliesTo = r.getAppliesToJson() == null ? List.of()
                    : objectMapper.readValue(r.getAppliesToJson(), STRING_LIST);
        } catch (JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
        return PromotionRedemptionView.of(r.getId(), r.getPromotionId(), r.getPromotionName(),
                r.getCode(), r.getOwnerPartyId(), r.getPercentage(), appliesTo, r.getMonthsLeft());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BadRequestException("unserializable JSON value");
        }
    }
}
