package com.bss.devicecommerce.service;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.entity.TradeInValuation;
import com.bss.devicecommerce.events.DomainEventPublisher;
import com.bss.devicecommerce.exception.BadRequestException;
import com.bss.devicecommerce.exception.ConflictException;
import com.bss.devicecommerce.exception.NotFoundException;
import com.bss.devicecommerce.financing.FinancingMath;
import com.bss.devicecommerce.financing.FinancingProvider;
import com.bss.devicecommerce.financing.FinancingProviders;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.repository.TradeInValuationRepository;
import com.bss.devicecommerce.security.PartyScope;
import com.bss.devicecommerce.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Device financing agreements and the upgrade/swap saga. The financing
 * model is a driver behind the FinancingProvider port; this service owns
 * the state machine and the events, never the model's economics.
 */
@Service
public class DeviceAgreementService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAgreementService.class);

    private static final Set<String> MODELS = Set.of(DeviceAgreement.OPERATOR_BOOK,
            DeviceAgreement.THIRD_PARTY_LOAN, DeviceAgreement.BNPL);
    /** Valuation states that stand behind a swap (money not yet moved is fine;
     * the grading delta settles separately). */
    private static final Set<String> SWAP_READY = Set.of(TradeInValuation.ACCEPTED,
            TradeInValuation.IN_TRANSIT, TradeInValuation.GRADED, TradeInValuation.REVALUED,
            TradeInValuation.SETTLED);

    private final DeviceAgreementRepository agreements;
    private final TradeInValuationRepository valuations;
    private final FinancingProviders providers;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;

    public DeviceAgreementService(DeviceAgreementRepository agreements,
            TradeInValuationRepository valuations, FinancingProviders providers,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope) {
        this.agreements = agreements;
        this.valuations = valuations;
        this.providers = providers;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /** Price a financing across models — the checkout's chooser face. */
    public Map<String, Object> quote(Map<String, Object> terms) {
        if (terms.get("principal") == null || terms.get("termMonths") == null) {
            throw new BadRequestException("principal and termMonths are required");
        }
        String model = terms.get("financingModel") == null ? DeviceAgreement.OPERATOR_BOOK
                : String.valueOf(terms.get("financingModel"));
        return providers.forModel(model).quote(terms);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        for (String required : List.of("principal", "termMonths", "financingModel",
                "totalCostOfOwnership")) {
            if (dto.get(required) == null) {
                // TCO is deliberately not derived: the channel must have SHOWN it
                throw new BadRequestException(required + " is required"
                        + ("totalCostOfOwnership".equals(required)
                        ? " — the total cost belongs on the offer face, not fine print" : ""));
            }
        }
        String model = String.valueOf(dto.get("financingModel"));
        if (!MODELS.contains(model)) {
            throw new BadRequestException("financingModel must be one of " + MODELS);
        }
        DeviceAgreement a = new DeviceAgreement();
        a.setId(UUID.randomUUID().toString());
        a.setTenantId(tenantScope.currentTenantId());
        a.setHref(ApiConstants.BASE_PATH + "/deviceAgreement/" + a.getId());
        a.setPartyId(relatedPartyId(dto));
        // a customer signs only their own agreement, whatever they send
        partyScope.scopedPartyId().ifPresent(a::setPartyId);
        if (a.getPartyId() == null) {
            throw new BadRequestException("relatedParty is required");
        }
        a.setSubscriptionRef(str(dto.get("subscriptionRef")));
        a.setOrderRef(str(dto.get("orderRef")));
        a.setDeviceRef(str(dto.get("deviceRef")));
        a.setImei(str(dto.get("imei")));
        a.setSerialNo(str(dto.get("serialNo")));
        a.setPrincipal(money(dto.get("principal")));
        a.setTermMonths(Integer.parseInt(String.valueOf(dto.get("termMonths"))));
        if (a.getPrincipal().signum() <= 0 || a.getTermMonths() < 1) {
            throw new BadRequestException("principal must be positive and termMonths >= 1");
        }
        a.setMonthlyAmount(dto.get("monthlyAmount") == null
                ? FinancingMath.monthly(a.getPrincipal(), a.getTermMonths())
                : money(dto.get("monthlyAmount")));
        a.setFinancingModel(model);
        a.setFinancierRef(str(dto.get("financierRef")));
        a.setExternalAgreementNo(str(dto.get("externalAgreementNo")));
        a.setTitleHolder(str(dto.get("titleHolder")));
        if (dto.get("upgradeRule") instanceof Map<?, ?> rule) {
            if (rule.get("paidSharePct") != null) {
                a.setUpgradeRuleType("paidSharePct");
                a.setUpgradeRuleValue(money(rule.get("paidSharePct")));
            } else if (rule.get("month") != null) {
                a.setUpgradeRuleType("month");
                a.setUpgradeRuleValue(money(rule.get("month")));
            }
        }
        a.setResidualValue(dto.get("residualValue") == null ? null : money(dto.get("residualValue")));
        a.setTotalCostOfOwnership(money(dto.get("totalCostOfOwnership")));
        a.setSubsidyAmount(dto.get("subsidyAmount") == null ? null : money(dto.get("subsidyAmount")));
        a.setShippingCost(dto.get("shippingCost") == null ? null : money(dto.get("shippingCost")));
        a.setCurrency(dto.get("currency") == null ? "EUR" : String.valueOf(dto.get("currency")));
        a.setPaymentRef(str(dto.get("paymentRef")));
        a.setInstallmentsPaid(0);
        a.setStatus(DeviceAgreement.ACTIVE);
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());

        FinancingProvider provider = providers.forModel(model);
        provider.originate(a, dto);
        agreements.save(a);
        Map<String, Object> view = view(a);
        events.publish("DeviceAgreementActivated", "deviceAgreement", view);
        // the mock bank approves and pays out in-process (deterministic for
        // e2e); a real bank hits the payoutWebhook endpoint instead
        if (DeviceAgreement.THIRD_PARTY_LOAN.equals(model)) {
            provider.payoutReceived(a);
            agreements.save(a);
            view = view(a);
            events.publish("FinancingPayoutReceived", "deviceAgreement", view);
        }
        log.info("device agreement {} {} {} for {} ({} x {})", a.getId(), model,
                a.getPrincipal(), a.getPartyId(), a.getTermMonths(), a.getMonthlyAmount());
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return agreements.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(a -> scoped == null || scoped.equals(a.getPartyId()))
                .filter(a -> status == null || status.equals(a.getStatus()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return view(own(id));
    }

    /**
     * One instalment landed (bill run / dunning feed; staff or machine).
     * Advances the local operator-book schedule and emits the monthly
     * contract-asset unwind for revenue — the last part takes the rounding
     * remainder so the unwinds sum exactly to the subsidy.
     */
    @Transactional
    public Map<String, Object> recordInstallment(String id) {
        DeviceAgreement a = own(id);
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("agreement is " + a.getStatus() + " — no schedule to advance");
        }
        if (a.getInstallmentsPaid() >= a.getTermMonths()) {
            return view(a);   // schedule already complete — idempotent
        }
        a.setInstallmentsPaid(a.getInstallmentsPaid() + 1);
        a.setLastUpdate(OffsetDateTime.now());
        boolean complete = a.getInstallmentsPaid() >= a.getTermMonths();
        if (complete) {
            a.setStatus(DeviceAgreement.SETTLED);
        }
        agreements.save(a);
        if (a.getSubsidyAmount() != null && a.getSubsidyAmount().signum() > 0
                && DeviceAgreement.OPERATOR_BOOK.equals(a.getFinancingModel())) {
            Map<String, Object> unwind = new LinkedHashMap<>();
            unwind.put("agreementId", a.getId());
            unwind.put("installmentNo", a.getInstallmentsPaid());
            unwind.put("unwindAmount", unwindAmount(a));
            unwind.put("currency", a.getCurrency());
            unwind.put("@type", "DeviceInstallment");
            events.publish("DeviceInstallmentRecordedEvent", "deviceInstallment", unwind);
        }
        Map<String, Object> view = view(a);
        if (complete) {
            events.publish("DeviceAgreementSettled", "deviceAgreement", view);
        }
        return view;
    }

    /** Early termination without a swap: the ETF recovers the unearned subsidy. */
    @Transactional
    public Map<String, Object> settle(String id, Map<String, Object> dto) {
        DeviceAgreement a = own(id);
        if (DeviceAgreement.SETTLED.equals(a.getStatus())) {
            return view(a);   // idempotent
        }
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("only active agreements settle (is " + a.getStatus() + ")");
        }
        BigDecimal etf = dto != null && dto.get("etfAmount") != null
                ? money(dto.get("etfAmount")) : BigDecimal.ZERO;
        a.setStatus(DeviceAgreement.SETTLED);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        Map<String, Object> view = view(a);
        view.put("etfAmount", etf);
        view.put("remainingSubsidy", remainingSubsidy(a));
        events.publish("DeviceAgreementSettled", "deviceAgreement", view);
        return view;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> earlySettlementQuote(String id) {
        DeviceAgreement a = own(id);
        Map<String, Object> quote = providers.forModel(a.getFinancingModel()).earlySettlementQuote(a);
        quote.put("agreementId", a.getId());
        quote.put("currency", a.getCurrency());
        return quote;
    }

    /** A real financier's payout callback (machine). Idempotent. */
    @Transactional
    public Map<String, Object> payoutWebhook(String id) {
        DeviceAgreement a = own(id);
        if (a.getPayoutReceivedAt() != null) {
            return view(a);
        }
        providers.forModel(a.getFinancingModel()).payoutReceived(a);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        Map<String, Object> view = view(a);
        events.publish("FinancingPayoutReceived", "deviceAgreement", view);
        return view;
    }

    /**
     * The upgrade/swap saga, one transaction per step boundary that matters:
     * eligibility → an accepted trade-in stands behind it → model-specific
     * settlement (write-off / bank early settlement / delegated BNPL) →
     * swapped. Replaying a completed swap returns the same answer.
     */
    @Transactional
    public Map<String, Object> swap(String id, Map<String, Object> dto) {
        DeviceAgreement a = own(id);
        if (DeviceAgreement.SWAPPED.equals(a.getStatus())) {
            return view(a);   // idempotent replay
        }
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("only active agreements swap (is " + a.getStatus() + ")");
        }
        Map<String, Object> eligibility = eligibility(a);
        if (!Boolean.TRUE.equals(eligibility.get("eligible"))) {
            throw new ConflictException("not yet upgrade-eligible: " + eligibility.get("reason"));
        }
        String valuationId = dto == null ? null : str(dto.get("tradeInValuationId"));
        if (valuationId == null) {
            throw new BadRequestException("tradeInValuationId is required — a swap trades the old device in");
        }
        TradeInValuation v = valuations.findByIdAndTenantId(valuationId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("TradeInValuation", valuationId));
        if (!SWAP_READY.contains(v.getStatus())) {
            throw new ConflictException("trade-in valuation must be accepted (is " + v.getStatus() + ")");
        }
        BigDecimal tradeInValue = v.getFinalValue() != null ? v.getFinalValue() : v.getEstimatedValue();
        Map<String, Object> settlement = providers.forModel(a.getFinancingModel())
                .settle(a, tradeInValue);
        a.setStatus(DeviceAgreement.SWAPPED);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        v.setAgreementRef(a.getId());
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        Map<String, Object> view = view(a);
        view.put("settlement", settlement);
        view.put("tradeInValuationId", v.getId());
        view.put("remainingSubsidy", remainingSubsidy(a));
        events.publish("DeviceAgreementSwapped", "deviceAgreement", view);
        log.info("device agreement {} swapped ({}): trade-in {} at {}", a.getId(),
                a.getFinancingModel(), v.getId(), tradeInValue);
        return view;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> eligibilityOf(String id) {
        return eligibility(own(id));
    }

    /* ---------- internals shared with listeners/withdrawal ---------- */

    Map<String, Object> eligibility(DeviceAgreement a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreementId", a.getId());
        out.put("paidSharePct", FinancingMath.paidSharePct(a));
        out.put("installmentsPaid", a.getInstallmentsPaid());
        if (a.getUpgradeRuleType() == null) {
            out.put("eligible", true);
            out.put("reason", "no upgrade rule on the agreement");
            return out;
        }
        boolean eligible;
        String reason;
        if ("paidSharePct".equals(a.getUpgradeRuleType())) {
            eligible = FinancingMath.paidSharePct(a).compareTo(a.getUpgradeRuleValue()) >= 0;
            reason = "paid share " + FinancingMath.paidSharePct(a) + "% vs required "
                    + a.getUpgradeRuleValue() + "%";
        } else {
            eligible = BigDecimal.valueOf(a.getInstallmentsPaid())
                    .compareTo(a.getUpgradeRuleValue()) >= 0;
            reason = a.getInstallmentsPaid() + " instalments vs required month "
                    + a.getUpgradeRuleValue().stripTrailingZeros().toPlainString();
        }
        out.put("eligible", eligible);
        out.put("reason", reason);
        return out;
    }

    /** This month's unwind slice; the LAST instalment takes the remainder. */
    private BigDecimal unwindAmount(DeviceAgreement a) {
        BigDecimal per = a.getSubsidyAmount()
                .divide(BigDecimal.valueOf(a.getTermMonths()), 2, RoundingMode.HALF_UP);
        if (a.getInstallmentsPaid() == a.getTermMonths()) {
            BigDecimal before = per.multiply(BigDecimal.valueOf(a.getTermMonths() - 1));
            return a.getSubsidyAmount().subtract(before);
        }
        return per;
    }

    /** Subsidy not yet unwound — the contract asset still on the book. */
    private BigDecimal remainingSubsidy(DeviceAgreement a) {
        if (a.getSubsidyAmount() == null || a.getSubsidyAmount().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal per = a.getSubsidyAmount()
                .divide(BigDecimal.valueOf(a.getTermMonths()), 2, RoundingMode.HALF_UP);
        int months = Math.min(a.getInstallmentsPaid(), a.getTermMonths());
        BigDecimal unwound = per.multiply(BigDecimal.valueOf(months));
        BigDecimal remaining = a.getSubsidyAmount().subtract(unwound);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining.setScale(2, RoundingMode.HALF_UP);
    }

    DeviceAgreement own(String id) {
        DeviceAgreement a = agreements.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("DeviceAgreement", id));
        partyScope.scopedPartyId().ifPresent(party -> {
            if (!party.equals(a.getPartyId())) {
                throw NotFoundException.forResource("DeviceAgreement", id);
            }
        });
        return a;
    }

    Map<String, Object> view(DeviceAgreement a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        map.put("financingModel", a.getFinancingModel());
        map.put("status", a.getStatus());
        if (a.getSubscriptionRef() != null) map.put("subscriptionRef", a.getSubscriptionRef());
        if (a.getOrderRef() != null) map.put("orderRef", a.getOrderRef());
        if (a.getDeviceRef() != null) map.put("device", deviceRef(a));
        map.put("principal", a.getPrincipal());
        map.put("termMonths", a.getTermMonths());
        map.put("monthlyAmount", a.getMonthlyAmount());
        map.put("totalCostOfOwnership", a.getTotalCostOfOwnership());
        map.put("currency", a.getCurrency());
        map.put("installmentsPaid", a.getInstallmentsPaid());
        map.put("paidSharePct", FinancingMath.paidSharePct(a));
        map.put("remainingPrincipal", FinancingMath.remainingPrincipal(a));
        if (a.getFinancierRef() != null) map.put("financierRef", a.getFinancierRef());
        if (a.getExternalAgreementNo() != null) map.put("externalAgreementNo", a.getExternalAgreementNo());
        if (a.getTitleHolder() != null) map.put("titleHolder", a.getTitleHolder());
        if (a.getUpgradeRuleType() != null) {
            map.put("upgradeRule", Map.of(a.getUpgradeRuleType(), a.getUpgradeRuleValue()));
        }
        if (a.getResidualValue() != null) map.put("residualValue", a.getResidualValue());
        if (a.getSubsidyAmount() != null) map.put("subsidyAmount", a.getSubsidyAmount());
        if (a.getShippingCost() != null) map.put("shippingCost", a.getShippingCost());
        if (a.getPaymentRef() != null) map.put("paymentRef", a.getPaymentRef());
        if (a.getPayoutReceivedAt() != null) map.put("payoutReceivedAt", a.getPayoutReceivedAt().toString());
        if (a.getDeliveredAt() != null) map.put("deliveredAt", a.getDeliveredAt().toString());
        if (a.getTradeInDelta() != null) map.put("tradeInDelta", a.getTradeInDelta());
        map.put("relatedParty", List.of(Map.of("id", a.getPartyId(), "role", "customer")));
        map.put("@type", "DeviceAgreement");
        return map;
    }

    private Map<String, Object> deviceRef(DeviceAgreement a) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("id", a.getDeviceRef());
        if (a.getImei() != null) device.put("imei", a.getImei());
        if (a.getSerialNo() != null) device.put("serialNumber", a.getSerialNo());
        device.put("@referredType", "LogicalResource");
        return device;
    }

    private static String relatedPartyId(Map<String, Object> dto) {
        if (dto.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            return String.valueOf(party.get("id"));
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal money(Object v) {
        try {
            return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + v + "' is not an amount");
        }
    }
}
