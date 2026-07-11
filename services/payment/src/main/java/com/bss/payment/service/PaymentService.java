package com.bss.payment.service;

import com.bss.payment.api.ApiConstants;
import com.bss.payment.api.OffsetPageRequest;
import com.bss.payment.api.PagedResult;
import com.bss.payment.dto.MoneyDto;
import com.bss.payment.dto.PaymentDto;
import com.bss.payment.entity.Payment;
import com.bss.payment.events.DomainEventPublisher;
import com.bss.payment.client.PaymentMethodClient;
import com.bss.payment.exception.BadRequestException;
import com.bss.payment.exception.ConflictException;
import com.bss.payment.exception.NotFoundException;
import com.bss.payment.psp.PspAdapter;
import com.bss.payment.repository.PaymentRepository;
import com.bss.payment.security.PartyScope;
import com.bss.payment.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentService {

    private static final String RESOURCE = "Payment";

    /** The only legal moves: an authorization is either taken or given back. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            Payment.AUTHORIZED, Set.of(Payment.CAPTURED, Payment.VOIDED));

    private final PaymentRepository repository;
    private final PspAdapter psp;
    private final PaymentMethodClient paymentMethods;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public PaymentService(PaymentRepository repository, PspAdapter psp, PaymentMethodClient paymentMethods, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope) {
        this.repository = repository;
        this.psp = psp;
        this.paymentMethods = paymentMethods;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<PaymentDto> findAll(int offset, int limit, Map<String, String> filters) {
        Payment probe = probeFor(filters);
        probe.setTenantId(tenantScope.currentTenantId());
        // Customers see their own payments only, whatever else they filter on.
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Payment> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toDto).toList(), page.getTotalElements());
    }

    private Payment probeFor(Map<String, String> filters) {
        Payment probe = new Payment();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "correlatorId" -> probe.setCorrelatorId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public PaymentDto findById(String id) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toDto(entity);
    }

    /**
     * Creating a payment IS the authorization: the PSP approves or the request
     * fails with a 409 and nothing is stored. Card details are used for the
     * PSP call only.
     */
    @Transactional
    public PaymentDto create(PaymentDto dto) {
        if (dto.getAmount() == null || dto.getAmount().getValue() == null
                || dto.getAmount().getValue().signum() <= 0) {
            throw new BadRequestException("amount must be positive");
        }
        String currency = dto.getAmount().getUnit() == null ? "EUR" : dto.getAmount().getUnit();
        Map<String, Object> method = dto.getPaymentMethod();
        // TMF670 seam: a saved method arrives as a reference, never as card
        // data. Resolve it in the vault (machine call), prove it belongs to
        // the payer, and pay with the vault token.
        if (method != null && method.get("id") != null && method.get("cardNumber") == null) {
            Map<String, Object> saved = paymentMethods.resolve(String.valueOf(method.get("id")));
            if (saved == null) {
                throw new BadRequestException("saved payment method not found");
            }
            Object methodOwner = ((java.util.List<Map<String, Object>>) saved.getOrDefault(
                    "relatedParty", java.util.List.of())).stream().map(p -> p.get("id")).findFirst().orElse(null);
            String payer = partyScope.scopedPartyId().orElse(null);
            if (payer != null && !payer.equals(methodOwner)) {
                throw new BadRequestException("saved payment method not found");
            }
            method = (Map<String, Object>) saved.get("details");
        }
        PspAdapter.Authorization auth = psp.authorize(
                dto.getAmount().getValue(), currency, method);
        if (!auth.approved()) {
            throw new ConflictException(auth.declineReason());
        }

        Payment entity = new Payment();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/payment/" + id);
        entity.setDescription(dto.getDescription());
        entity.setStatus(Payment.AUTHORIZED);
        entity.setAmountValue(dto.getAmount().getValue());
        entity.setAmountUnit(currency);
        entity.setMethodType(dto.getPaymentMethod() == null ? null
                : String.valueOf(dto.getPaymentMethod().getOrDefault("@type", "bankCard")));
        entity.setMethodLabel(auth.methodLabel());
        entity.setAuthorizationCode(auth.authorizationCode());
        entity.setCorrelatorId(dto.getCorrelatorId());
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setPaymentDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto created = toDto(repository.save(entity));
        events.publish("PaymentCreateEvent", "payment", created);
        return created;
    }

    /** Status transitions (capture/void) and correlator linkage; nothing else changes after authorization. */
    @Transactional
    public PaymentDto patch(String id, PaymentDto patch) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (patch.getCorrelatorId() != null) {
            entity.setCorrelatorId(patch.getCorrelatorId());
        }
        if (patch.getStatus() != null && !patch.getStatus().equals(entity.getStatus())) {
            Set<String> allowed = TRANSITIONS.getOrDefault(entity.getStatus(), Set.of());
            if (!allowed.contains(patch.getStatus())) {
                throw new ConflictException("payment is '" + entity.getStatus()
                        + "' and cannot become '" + patch.getStatus() + "'");
            }
            entity.setStatus(patch.getStatus());
        }
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto updated = toDto(repository.save(entity));
        events.publish("PaymentStateChangeEvent", "payment", updated);
        return updated;
    }

    /**
     * Scoped tokens address only their own payments; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(Payment entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private PaymentDto toDto(Payment entity) {
        PaymentDto dto = new PaymentDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setAmount(new MoneyDto(entity.getAmountUnit(), entity.getAmountValue()));
        if (entity.getMethodLabel() != null) {
            dto.setPaymentMethod(Map.of("@type", entity.getMethodType(), "label", entity.getMethodLabel()));
        }
        dto.setAuthorizationCode(entity.getAuthorizationCode());
        dto.setCorrelatorId(entity.getCorrelatorId());
        if (entity.getOwnerPartyId() != null) {
            dto.setRelatedParty(List.of(Map.of(
                    "id", entity.getOwnerPartyId(), "role", "payer", "@referredType", "Individual")));
        }
        dto.setPaymentDate(entity.getPaymentDate());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("Payment");
        return dto;
    }
}
