package com.bss.devicecommerce.service;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.DeviceAgreementView;
import com.bss.devicecommerce.dto.DeviceInstallmentUnwind;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.dto.SettleReceipt;
import com.bss.devicecommerce.dto.SettleRequest;
import com.bss.devicecommerce.dto.SwapReceipt;
import com.bss.devicecommerce.dto.SwapRequest;
import com.bss.devicecommerce.dto.UpgradeEligibility;
import com.bss.devicecommerce.dto.UpgradeRule;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.entity.TradeInValuation;
import com.bss.devicecommerce.events.DomainEventPublisher;
import com.bss.devicecommerce.exception.BadRequestException;
import com.bss.devicecommerce.exception.ConflictException;
import com.bss.devicecommerce.exception.NotFoundException;
import com.bss.devicecommerce.financing.FinancingMath;
import com.bss.devicecommerce.financing.FinancingProvider;
import com.bss.devicecommerce.financing.FinancingProviders;
import com.bss.devicecommerce.mapper.DeviceAgreementMapper;
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
import java.util.List;
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

    /** In declaration order, so the refusal names them the same way on every JVM. */
    private static final List<String> MODELS = List.of(DeviceAgreement.OPERATOR_BOOK,
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
    public FinancingQuote quote(FinancingTerms terms) {
        if (terms.principal() == null || terms.termMonths() == null) {
            throw new BadRequestException("principal and termMonths are required");
        }
        String model = terms.financingModel() == null ? DeviceAgreement.OPERATOR_BOOK : terms.financingModel();
        return providers.forModel(model).quote(terms);
    }

    @Transactional
    public DeviceAgreementView create(DeviceAgreementRequest dto) {
        if (dto.principal() == null) {
            throw new BadRequestException("principal is required");
        }
        if (dto.termMonths() == null) {
            throw new BadRequestException("termMonths is required");
        }
        if (dto.financingModel() == null) {
            throw new BadRequestException("financingModel is required");
        }
        if (dto.totalCostOfOwnership() == null) {
            // TCO is deliberately not derived: the channel must have SHOWN it
            throw new BadRequestException("totalCostOfOwnership is required"
                    + " — the total cost belongs on the offer face, not fine print");
        }
        String model = dto.financingModel();
        if (!MODELS.contains(model)) {
            throw new BadRequestException("financingModel must be one of " + MODELS);
        }
        DeviceAgreement a = new DeviceAgreement();
        a.setId(UUID.randomUUID().toString());
        a.setTenantId(tenantScope.currentTenantId());
        a.setHref(ApiConstants.BASE_PATH + "/deviceAgreement/" + a.getId());
        a.setPartyId(dto.relatedPartyId());
        // a customer signs only their own agreement, whatever they send
        partyScope.scopedPartyId().ifPresent(a::setPartyId);
        if (a.getPartyId() == null) {
            throw new BadRequestException("relatedParty is required");
        }
        a.setSubscriptionRef(dto.subscriptionRef());
        a.setOrderRef(dto.orderRef());
        a.setDeviceRef(dto.deviceRef());
        a.setImei(dto.imei());
        a.setSerialNo(dto.serialNo());
        a.setPrincipal(money(dto.principal()));
        a.setTermMonths(dto.termMonths());
        if (a.getPrincipal().signum() <= 0 || a.getTermMonths() < 1) {
            throw new BadRequestException("principal must be positive and termMonths >= 1");
        }
        a.setMonthlyAmount(dto.monthlyAmount() == null
                ? FinancingMath.monthly(a.getPrincipal(), a.getTermMonths())
                : money(dto.monthlyAmount()));
        a.setFinancingModel(model);
        a.setFinancierRef(dto.financierRef());
        a.setExternalAgreementNo(dto.externalAgreementNo());
        a.setTitleHolder(dto.titleHolder());
        UpgradeRule rule = dto.upgradeRule();
        if (rule != null && rule.type() != null) {
            a.setUpgradeRuleType(rule.type());
            a.setUpgradeRuleValue(money(rule.value()));
        }
        a.setResidualValue(money(dto.residualValue()));
        a.setTotalCostOfOwnership(money(dto.totalCostOfOwnership()));
        a.setSubsidyAmount(money(dto.subsidyAmount()));
        a.setShippingCost(money(dto.shippingCost()));
        a.setCurrency(dto.currency() == null ? "EUR" : dto.currency());
        a.setPaymentRef(dto.paymentRef());
        a.setInstallmentsPaid(0);
        a.setStatus(DeviceAgreement.ACTIVE);
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());

        FinancingProvider provider = providers.forModel(model);
        provider.originate(a, dto);
        agreements.save(a);
        DeviceAgreementView view = view(a);
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
    public List<DeviceAgreementView> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return agreements.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(a -> scoped == null || scoped.equals(a.getPartyId()))
                .filter(a -> status == null || status.equals(a.getStatus()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DeviceAgreementView findById(String id) {
        return view(own(id));
    }

    /**
     * One instalment landed (bill run / dunning feed; staff or machine).
     * Advances the local operator-book schedule and emits the monthly
     * contract-asset unwind for revenue — the last part takes the rounding
     * remainder so the unwinds sum exactly to the subsidy.
     */
    @Transactional
    public DeviceAgreementView recordInstallment(String id) {
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
            events.publish("DeviceInstallmentRecordedEvent", "deviceInstallment",
                    DeviceInstallmentUnwind.of(a.getId(), a.getInstallmentsPaid(), unwindAmount(a),
                            a.getCurrency()));
        }
        DeviceAgreementView view = view(a);
        if (complete) {
            events.publish("DeviceAgreementSettled", "deviceAgreement", view);
        }
        return view;
    }

    /** Early termination without a swap: the ETF recovers the unearned subsidy. */
    @Transactional
    public SettleReceipt settle(String id, SettleRequest dto) {
        DeviceAgreement a = own(id);
        if (DeviceAgreement.SETTLED.equals(a.getStatus())) {
            return SettleReceipt.unchanged(view(a));   // idempotent
        }
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("only active agreements settle (is " + a.getStatus() + ")");
        }
        BigDecimal etf = dto != null && dto.etfAmount() != null ? money(dto.etfAmount()) : BigDecimal.ZERO;
        a.setStatus(DeviceAgreement.SETTLED);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        SettleReceipt receipt = new SettleReceipt(view(a), etf, remainingSubsidy(a));
        events.publish("DeviceAgreementSettled", "deviceAgreement", receipt);
        return receipt;
    }

    @Transactional(readOnly = true)
    public EarlySettlementQuote earlySettlementQuote(String id) {
        DeviceAgreement a = own(id);
        return providers.forModel(a.getFinancingModel()).earlySettlementQuote(a)
                .forAgreement(a.getId(), a.getCurrency());
    }

    /** A real financier's payout callback (machine). Idempotent. */
    @Transactional
    public DeviceAgreementView payoutWebhook(String id) {
        DeviceAgreement a = own(id);
        if (a.getPayoutReceivedAt() != null) {
            return view(a);
        }
        providers.forModel(a.getFinancingModel()).payoutReceived(a);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        DeviceAgreementView view = view(a);
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
    public SwapReceipt swap(String id, SwapRequest dto) {
        DeviceAgreement a = own(id);
        if (DeviceAgreement.SWAPPED.equals(a.getStatus())) {
            return SwapReceipt.unchanged(view(a));   // idempotent replay
        }
        if (!DeviceAgreement.ACTIVE.equals(a.getStatus())) {
            throw new ConflictException("only active agreements swap (is " + a.getStatus() + ")");
        }
        UpgradeEligibility eligibility = eligibility(a);
        if (!eligibility.eligible()) {
            throw new ConflictException("not yet upgrade-eligible: " + eligibility.reason());
        }
        String valuationId = dto == null ? null : dto.tradeInValuationId();
        if (valuationId == null) {
            throw new BadRequestException("tradeInValuationId is required — a swap trades the old device in");
        }
        TradeInValuation v = valuations.findByIdAndTenantId(valuationId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("TradeInValuation", valuationId));
        if (!SWAP_READY.contains(v.getStatus())) {
            throw new ConflictException("trade-in valuation must be accepted (is " + v.getStatus() + ")");
        }
        BigDecimal tradeInValue = v.getFinalValue() != null ? v.getFinalValue() : v.getEstimatedValue();
        FinancingSettlement settlement = providers.forModel(a.getFinancingModel())
                .settle(a, tradeInValue);
        a.setStatus(DeviceAgreement.SWAPPED);
        a.setLastUpdate(OffsetDateTime.now());
        agreements.save(a);
        v.setAgreementRef(a.getId());
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        SwapReceipt receipt = new SwapReceipt(view(a), settlement, v.getId(), remainingSubsidy(a));
        events.publish("DeviceAgreementSwapped", "deviceAgreement", receipt);
        log.info("device agreement {} swapped ({}): trade-in {} at {}", a.getId(),
                a.getFinancingModel(), v.getId(), tradeInValue);
        return receipt;
    }

    @Transactional(readOnly = true)
    public UpgradeEligibility eligibilityOf(String id) {
        return eligibility(own(id));
    }

    /* ---------- internals shared with listeners/withdrawal ---------- */

    UpgradeEligibility eligibility(DeviceAgreement a) {
        BigDecimal paidShare = FinancingMath.paidSharePct(a);
        if (a.getUpgradeRuleType() == null) {
            return new UpgradeEligibility(a.getId(), paidShare, a.getInstallmentsPaid(), true,
                    "no upgrade rule on the agreement");
        }
        boolean eligible;
        String reason;
        if (UpgradeRule.PAID_SHARE_PCT.equals(a.getUpgradeRuleType())) {
            eligible = paidShare.compareTo(a.getUpgradeRuleValue()) >= 0;
            reason = "paid share " + paidShare + "% vs required " + a.getUpgradeRuleValue() + "%";
        } else {
            eligible = BigDecimal.valueOf(a.getInstallmentsPaid())
                    .compareTo(a.getUpgradeRuleValue()) >= 0;
            reason = a.getInstallmentsPaid() + " instalments vs required month "
                    + a.getUpgradeRuleValue().stripTrailingZeros().toPlainString();
        }
        return new UpgradeEligibility(a.getId(), paidShare, a.getInstallmentsPaid(), eligible, reason);
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

    DeviceAgreementView view(DeviceAgreement a) {
        return DeviceAgreementMapper.view(a);
    }

    /** Money as the entity stores it: two decimals, half up; null stays null. */
    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
