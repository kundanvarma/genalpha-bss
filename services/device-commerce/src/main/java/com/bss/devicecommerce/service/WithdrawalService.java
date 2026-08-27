package com.bss.devicecommerce.service;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.entity.WithdrawalCase;
import com.bss.devicecommerce.events.DomainEventPublisher;
import com.bss.devicecommerce.exception.BadRequestException;
import com.bss.devicecommerce.exception.ConflictException;
import com.bss.devicecommerce.exception.NotFoundException;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.repository.WithdrawalCaseRepository;
import com.bss.devicecommerce.security.PartyScope;
import com.bss.devicecommerce.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 14-day withdrawal (angrerett, EU 2011/83): unconditional inside the
 * window, clock starts at physical receipt (the parcel-delivery event;
 * activation is the honest fallback), refund includes standard shipping,
 * and a deduction exists only WITH a recorded return grade — documented
 * diminished value, never a restocking fee in disguise.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final WithdrawalCaseRepository cases;
    private final DeviceAgreementRepository agreements;
    private final PaymentClient payments;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final int withdrawalDays;

    public WithdrawalService(WithdrawalCaseRepository cases, DeviceAgreementRepository agreements,
            PaymentClient payments, DomainEventPublisher events, TenantScope tenantScope,
            PartyScope partyScope, @Value("${bss.device.withdrawal-days:14}") int withdrawalDays) {
        this.cases = cases;
        this.agreements = agreements;
        this.payments = payments;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.withdrawalDays = withdrawalDays;
    }

    @Transactional
    public Map<String, Object> open(Map<String, Object> dto) {
        String agreementId = dto.get("agreementId") == null ? null
                : String.valueOf(dto.get("agreementId"));
        if (agreementId == null) {
            throw new BadRequestException("agreementId is required");
        }
        String tenant = tenantScope.currentTenantId();
        DeviceAgreement a = agreements.findByIdAndTenantId(agreementId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("DeviceAgreement", agreementId));
        partyScope.scopedPartyId().ifPresent(party -> {
            if (!party.equals(a.getPartyId())) {
                throw NotFoundException.forResource("DeviceAgreement", agreementId);
            }
        });
        WithdrawalCase existing = cases.findByTenantIdAndAgreementRef(tenant, agreementId).orElse(null);
        if (existing != null) {
            return view(existing);   // idempotent — one withdrawal per agreement
        }
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("only active agreements withdraw (is " + a.getStatus() + ")");
        }
        OffsetDateTime clockStart = a.getDeliveredAt() != null ? a.getDeliveredAt() : a.getCreatedAt();
        if (clockStart.plusDays(withdrawalDays).isBefore(OffsetDateTime.now())) {
            throw new ConflictException("the " + withdrawalDays + "-day withdrawal window closed on "
                    + clockStart.plusDays(withdrawalDays).toLocalDate());
        }
        BigDecimal deduction = dto.get("deduction") == null ? BigDecimal.ZERO : money(dto.get("deduction"));
        String returnGrade = dto.get("returnGrade") == null ? null : String.valueOf(dto.get("returnGrade"));
        if (deduction.signum() > 0 && returnGrade == null) {
            throw new BadRequestException(
                    "a deduction needs a recorded returnGrade — diminished value must be documented");
        }
        BigDecimal shipping = a.getShippingCost() == null ? BigDecimal.ZERO : a.getShippingCost();
        BigDecimal refund = a.getPrincipal().add(shipping).subtract(deduction);
        if (refund.signum() < 0) {
            refund = BigDecimal.ZERO;
        }
        String refundRef = a.getPaymentRef() == null ? null
                : payments.refund(a.getPaymentRef(), refund, "withdrawal " + agreementId);

        WithdrawalCase w = new WithdrawalCase();
        w.setId(UUID.randomUUID().toString());
        w.setTenantId(tenant);
        w.setHref(ApiConstants.BASE_PATH + "/withdrawalCase/" + w.getId());
        w.setPartyId(a.getPartyId());
        w.setOrderRef(a.getOrderRef());
        w.setAgreementRef(a.getId());
        w.setClockStart(clockStart);
        w.setReturnGrade(returnGrade);
        w.setDeduction(deduction);
        w.setRefundAmount(refund);
        w.setRefundRef(refundRef);
        w.setStatus(WithdrawalCase.REFUNDED);
        w.setCreatedAt(OffsetDateTime.now());
        w.setLastUpdate(OffsetDateTime.now());
        cases.save(w);

        a.setStatus(DeviceAgreement.WITHDRAWN);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);

        Map<String, Object> view = view(w);
        view.put("subsidyAmount", a.getSubsidyAmount());
        view.put("financingModel", a.getFinancingModel());
        view.put("currency", a.getCurrency());
        if (refundRef == null) {
            view.put("note", "no PSP payment on the agreement — refund recorded, paid out manually");
        }
        events.publish("DeviceAgreementWithdrawn", "withdrawalCase", view);
        log.info("withdrawal {} for agreement {}: refund {} (shipping {} deduction {})",
                w.getId(), a.getId(), refund, shipping, deduction);
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String relatedPartyId) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return cases.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(w -> scoped == null || scoped.equals(w.getPartyId()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        WithdrawalCase w = cases.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("WithdrawalCase", id));
        partyScope.scopedPartyId().ifPresent(party -> {
            if (!party.equals(w.getPartyId())) {
                throw NotFoundException.forResource("WithdrawalCase", id);
            }
        });
        return view(w);
    }

    private Map<String, Object> view(WithdrawalCase w) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", w.getId());
        map.put("href", w.getHref());
        map.put("agreementRef", w.getAgreementRef());
        if (w.getOrderRef() != null) map.put("orderRef", w.getOrderRef());
        map.put("status", w.getStatus());
        map.put("clockStart", w.getClockStart().toString());
        if (w.getReturnGrade() != null) map.put("returnGrade", w.getReturnGrade());
        if (w.getDeduction() != null) map.put("deduction", w.getDeduction());
        if (w.getRefundAmount() != null) map.put("refundAmount", w.getRefundAmount());
        if (w.getRefundRef() != null) map.put("refundRef", w.getRefundRef());
        if (w.getPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", w.getPartyId(), "role", "customer")));
        }
        map.put("@type", "WithdrawalCase");
        return map;
    }

    private static BigDecimal money(Object v) {
        try {
            return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + v + "' is not an amount");
        }
    }
}
