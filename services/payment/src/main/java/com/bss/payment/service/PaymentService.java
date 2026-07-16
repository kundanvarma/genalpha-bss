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
import com.bss.payment.exception.ScaRequiredException;
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
import java.util.Optional;
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
        // Idempotency: a retried authorization (same correlator, same payer)
        // returns the original payment instead of holding funds twice — the
        // single most important thing a payment API must get right.
        if (dto.getCorrelatorId() != null) {
            Optional<Payment> prior = repository.findFirstByTenantIdAndCorrelatorId(
                    tenantScope.currentTenantId(), dto.getCorrelatorId());
            if (prior.isPresent()) {
                return toDto(prior.get());
            }
        }

        PspAdapter.Authorization auth = psp.authorize(
                dto.getAmount().getValue(), currency, method, dto.getCorrelatorId());
        if (auth.requiresAction()) {
            // Strong customer authentication (3-D Secure / BankID): the channel
            // completes the challenge and retries with the same correlator.
            throw new ScaRequiredException(auth.actionUrl());
        }
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
        entity.setPspProvider(psp.provider());
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
            // The status transition is where money actually moves: capture
            // settles the held authorization, void/refund reverses it. If the
            // PSP rejects the movement, the transition fails — the record never
            // claims money moved when it didn't.
            if (Payment.CAPTURED.equals(patch.getStatus())) {
                PspAdapter.Capture capture = psp.capture(entity.getAuthorizationCode(),
                        entity.getAmountValue(), entity.getAmountUnit());
                if (!capture.settled()) {
                    throw new ConflictException("capture failed: " + capture.failureReason());
                }
                entity.setSettlementRef(capture.captureRef());
            } else if (Payment.VOIDED.equals(patch.getStatus())) {
                PspAdapter.Refund refund = psp.refund(entity.getAuthorizationCode(),
                        entity.getAmountValue(), entity.getAmountUnit());
                if (!refund.refunded()) {
                    throw new ConflictException("void/refund failed: " + refund.failureReason());
                }
                entity.setSettlementRef(refund.refundRef());
            }
            entity.setStatus(patch.getStatus());
        }
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto updated = toDto(repository.save(entity));
        events.publish("PaymentStateChangeEvent", "payment", updated);
        return updated;
    }

    /**
     * REFUND, partial or full: the PSP must confirm the movement before the
     * record changes; refunds accumulate and can never exceed what was
     * captured. A fully refunded payment says so in its status.
     */
    @Transactional
    public Map<String, Object> refund(String id, Map<String, Object> dto) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!Payment.CAPTURED.equals(entity.getStatus()) && !Payment.REFUNDED.equals(entity.getStatus())) {
            throw new ConflictException("only captured money can be refunded (status: "
                    + entity.getStatus() + ")");
        }
        java.math.BigDecimal amount = dto.get("amount") instanceof Map<?, ?> m && m.get("value") != null
                ? new java.math.BigDecimal(String.valueOf(m.get("value")))
                : entity.getAmountValue().subtract(entity.getRefundedAmount());
        java.math.BigDecimal refundable = entity.getAmountValue().subtract(entity.getRefundedAmount());
        if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
            throw new ConflictException("refundable is " + refundable + " " + entity.getAmountUnit()
                    + "; asked for " + amount);
        }
        PspAdapter.Refund refund = psp.refund(entity.getAuthorizationCode(),
                amount, entity.getAmountUnit());
        if (!refund.refunded()) {
            throw new ConflictException("refund failed: " + refund.failureReason());
        }
        entity.setRefundedAmount(entity.getRefundedAmount().add(amount));
        if (entity.getRefundedAmount().compareTo(entity.getAmountValue()) >= 0) {
            entity.setStatus(Payment.REFUNDED);
        }
        entity.setLastUpdate(OffsetDateTime.now());
        repository.save(entity);
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("paymentId", entity.getId());
        receipt.put("amount", Map.of("value", amount, "unit", entity.getAmountUnit()));
        receipt.put("refundRef", refund.refundRef());
        receipt.put("refundedTotal", entity.getRefundedAmount());
        receipt.put("status", entity.getStatus());
        receipt.put("reason", dto.get("reason") == null ? null : String.valueOf(dto.get("reason")));
        receipt.put("relatedParty", java.util.List.of(
                Map.of("id", entity.getOwnerPartyId(), "role", "customer")));
        receipt.put("@type", "Refund");
        events.publish("PaymentRefundEvent", "refund", receipt);
        return receipt;
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
        dto.setSettlementRef(entity.getSettlementRef());
        if (entity.getRefundedAmount() != null && entity.getRefundedAmount().signum() > 0) {
            dto.setRefundedAmount(entity.getRefundedAmount());
        }
        dto.setPspProvider(entity.getPspProvider());
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
