package com.bss.paymentmethod.service;

import com.bss.paymentmethod.api.ApiConstants;
import com.bss.paymentmethod.entity.PaymentMethod;
import com.bss.paymentmethod.events.DomainEventPublisher;
import com.bss.paymentmethod.exception.BadRequestException;
import com.bss.paymentmethod.exception.NotFoundException;
import com.bss.paymentmethod.repository.PaymentMethodRepository;
import com.bss.paymentmethod.security.PartyScope;
import com.bss.paymentmethod.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF670: the saved-methods vault. Only presentation data (brand, last
 * four, expiry) and an opaque PSP vault token live here — a PAN never
 * enters this system; in production the token comes from the PSP's
 * client-side tokenization, in dev we mint a mock one. Customers manage
 * their own methods (404-not-403); the payment service resolves tokens
 * machine-side when a saved method pays.
 */
@Service
public class PaymentMethodService {

    private static final String RESOURCE = "PaymentMethod";

    private final PaymentMethodRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public PaymentMethodService(PaymentMethodRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String owner = partyScope.scopedPartyId().orElseGet(() -> {
            if (dto.get("relatedParty") instanceof List<?> parties) {
                for (Object p : parties) {
                    if (p instanceof Map<?, ?> ref && ref.get("id") != null) {
                        return String.valueOf(ref.get("id"));
                    }
                }
            }
            return null;
        });
        if (owner == null) {
            throw new BadRequestException("relatedParty is required for unscoped callers");
        }
        if (!(dto.get("details") instanceof Map<?, ?> details)) {
            throw new BadRequestException("details {brand, lastFourDigits, expiry} are required");
        }
        PaymentMethod entity = new PaymentMethod();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/paymentMethod/" + id);
        entity.setOwnerPartyId(owner);
        entity.setMethodType(dto.get("@type") == null ? "bankCard" : String.valueOf(dto.get("@type")));
        entity.setBrand(details.get("brand") == null ? null : String.valueOf(details.get("brand")));
        entity.setLastFour(details.get("lastFourDigits") == null ? null
                : String.valueOf(details.get("lastFourDigits")));
        entity.setExpiry(details.get("expiry") == null ? null : String.valueOf(details.get("expiry")));
        // Dev vault: mint an opaque token. Production: PSP tokenization result.
        entity.setPspToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        entity.setPreferred(Boolean.TRUE.equals(dto.get("preferred")));
        entity.setStatus(PaymentMethod.ACTIVE);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity), true);
        events.publish("PaymentMethodCreateEvent", "paymentMethod", created);
        return created;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(String requestedPartyId) {
        String party = partyScope.scopedPartyId().orElse(requestedPartyId);
        if (party == null) {
            throw new BadRequestException("relatedPartyId is required for unscoped callers");
        }
        return repository.findByTenantIdAndOwnerPartyIdAndStatus(
                        tenantScope.currentTenantId(), party, PaymentMethod.ACTIVE)
                .stream().map(m -> toMap(m, false)).toList();
    }

    /** Machine-side resolution when a saved method pays: includes the vault token. */
    @Transactional(readOnly = true)
    public Map<String, Object> resolve(String id) {
        PaymentMethod entity = active(id);
        requireOwn(entity);
        return toMap(entity, true);
    }

    @Transactional
    public void delete(String id) {
        PaymentMethod entity = active(id);
        requireOwn(entity);
        entity.setStatus(PaymentMethod.DELETED);
        entity.setLastUpdate(OffsetDateTime.now());
        repository.save(entity);
        events.publish("PaymentMethodDeleteEvent", "paymentMethod", toMap(entity, false));
    }

    private PaymentMethod active(String id) {
        PaymentMethod entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (!PaymentMethod.ACTIVE.equals(entity.getStatus())) {
            throw NotFoundException.forResource(RESOURCE, id);
        }
        return entity;
    }

    private void requireOwn(PaymentMethod entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private Map<String, Object> toMap(PaymentMethod m, boolean includeToken) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("href", m.getHref());
        map.put("@type", m.getMethodType());
        map.put("status", m.getStatus());
        map.put("preferred", m.isPreferred());
        Map<String, Object> details = new LinkedHashMap<>();
        if (m.getBrand() != null) details.put("brand", m.getBrand());
        if (m.getLastFour() != null) details.put("lastFourDigits", m.getLastFour());
        if (m.getExpiry() != null) details.put("expiry", m.getExpiry());
        if (includeToken) details.put("token", m.getPspToken());
        map.put("details", details);
        map.put("relatedParty", List.of(Map.of("id", m.getOwnerPartyId(), "role", "customer")));
        return map;
    }
}
