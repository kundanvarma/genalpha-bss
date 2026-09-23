package com.bss.paymentmethod.service;

import com.bss.paymentmethod.api.ApiConstants;
import com.bss.paymentmethod.dto.CardDetails;
import com.bss.paymentmethod.dto.PartyRef;
import com.bss.paymentmethod.dto.PaymentMethodRequest;
import com.bss.paymentmethod.dto.PaymentMethodView;
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
import java.util.List;
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
    public PaymentMethodView create(PaymentMethodRequest dto) {
        String owner = partyScope.scopedPartyId().orElseGet(dto::firstPartyId);
        if (owner == null) {
            throw new BadRequestException("relatedParty is required for unscoped callers");
        }
        if (dto.details() == null || !dto.details().isObject()) {
            throw new BadRequestException("details {brand, lastFourDigits, expiry} are required");
        }
        PaymentMethod entity = new PaymentMethod();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/paymentMethod/" + id);
        entity.setOwnerPartyId(owner);
        entity.setMethodType(dto.type() == null ? "bankCard" : dto.type());
        entity.setBrand(dto.detail("brand"));
        entity.setLastFour(dto.detail("lastFourDigits"));
        entity.setExpiry(dto.detail("expiry"));
        // Dev vault: mint an opaque token. Production: PSP tokenization result.
        // EXCEPTION — a bnplToken method's token IS the provider's recurring
        // token (Klarna minted it; we only hold the reference): keep it.
        if ("bnplToken".equals(entity.getMethodType()) && dto.detail("token") != null) {
            entity.setPspToken(dto.detail("token"));
        } else {
            entity.setPspToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        }
        entity.setPreferred(dto.preferredOrFalse());
        entity.setStatus(PaymentMethod.ACTIVE);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentMethodView created = toView(repository.save(entity), true);
        events.publish("PaymentMethodCreateEvent", "paymentMethod", created);
        return created;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodView> mine(String requestedPartyId) {
        String party = partyScope.scopedPartyId().orElse(requestedPartyId);
        if (party == null) {
            throw new BadRequestException("relatedPartyId is required for unscoped callers");
        }
        return repository.findByTenantIdAndOwnerPartyIdAndStatus(
                        tenantScope.currentTenantId(), party, PaymentMethod.ACTIVE)
                .stream().map(m -> toView(m, false)).toList();
    }

    /** Machine-side resolution when a saved method pays: includes the vault token. */
    @Transactional(readOnly = true)
    public PaymentMethodView resolve(String id) {
        PaymentMethod entity = active(id);
        requireOwn(entity);
        return toView(entity, true);
    }

    @Transactional
    public void delete(String id) {
        PaymentMethod entity = active(id);
        requireOwn(entity);
        entity.setStatus(PaymentMethod.DELETED);
        entity.setLastUpdate(OffsetDateTime.now());
        repository.save(entity);
        events.publish("PaymentMethodDeleteEvent", "paymentMethod", toView(entity, false));
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

    private PaymentMethodView toView(PaymentMethod m, boolean includeToken) {
        CardDetails details = CardDetails.presentation(m.getBrand(), m.getLastFour(),
                m.getExpiry());
        return new PaymentMethodView(m.getId(), m.getHref(), m.getMethodType(), m.getStatus(),
                m.isPreferred(), includeToken ? details.withToken(m.getPspToken()) : details,
                List.of(PartyRef.customer(m.getOwnerPartyId())));
    }
}
