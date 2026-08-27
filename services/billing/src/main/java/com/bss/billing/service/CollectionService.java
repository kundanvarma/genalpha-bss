package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CollectionCase;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.DunningPolicy;
import com.bss.billing.entity.InstallmentPlan;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.exception.ConflictException;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CollectionCaseRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.DunningPolicyRepository;
import com.bss.billing.repository.InstallmentPlanRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantContext;
import com.bss.billing.security.TenantRegistry;
import com.bss.billing.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COLLECTIONS: the ladder between a missed payment and a lost customer.
 * One case per account aggregates ALL its overdue bills; each sweep advances
 * at most one policy step per account — communication first, fees only where
 * the statutory pack allows them, enforcement only after the demand+warning
 * clock has genuinely run. Holds (promise-to-pay, amount-scoped dispute,
 * hardship) pause the ladder without deleting the position; any settlement
 * that clears the actionable balance CURES the case — services reinstate,
 * the ladder resets.
 *
 * bss.collections.compress-clocks (default false, NEVER in production):
 * statutory and policy day-spans are counted in SECONDS instead of days so a
 * proof run can live a whole delinquency in minutes. The statutory NUMBERS
 * stay identical either way — only the unit compresses.
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    /** One rung of the ladder. */
    public record Step(int offsetDays, String action, String templateId,
            String feeType, BigDecimal feeAmount) {

        public boolean hasFee() {
            return feeType != null && !feeType.isBlank() && !"none".equals(feeType);
        }

        public boolean isEnforcement() {
            return "restrict".equals(action) || "suspend".equals(action)
                    || "terminate".equals(action);
        }
    }

    private record Overdue(BigDecimal total, OffsetDateTime oldestDueAt, String currency,
            String oldestBillId, String oldestBillNo) {
    }

    private final CollectionCaseRepository cases;
    private final DunningPolicyRepository policies;
    private final CustomerBillRepository bills;
    private final InstallmentPlanRepository plans;
    private final AppliedBillingRateRepository rates;
    private final DisputeService disputeService;
    private final DownstreamClients.SomClient som;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final com.bss.billing.tick.TickGuard tickGuard;
    private final TenantClock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean compressClocks;
    // each account steps in its OWN transaction: one poisoned account loses
    // its step, never the tenant's whole sweep (same doctrine as the run)
    private final org.springframework.transaction.support.TransactionTemplate newTx;

    public CollectionService(CollectionCaseRepository cases, DunningPolicyRepository policies,
            CustomerBillRepository bills, InstallmentPlanRepository plans,
            AppliedBillingRateRepository rates, DisputeService disputeService,
            DownstreamClients.SomClient som, DomainEventPublisher events,
            TenantRegistry tenants, TenantScope tenantScope, PartyScope partyScope,
            com.bss.billing.tick.TickGuard tickGuard, TenantClock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @Value("${bss.collections.compress-clocks:false}") boolean compressClocks) {
        this.newTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cases = cases;
        this.policies = policies;
        this.bills = bills;
        this.plans = plans;
        this.rates = rates;
        this.disputeService = disputeService;
        this.som = som;
        this.events = events;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.tickGuard = tickGuard;
        this.clock = clock;
        this.compressClocks = compressClocks;
    }

    /** A statutory/policy day-span, compressed to seconds under the test flag. */
    private Duration span(long days) {
        return compressClocks ? Duration.ofSeconds(days) : Duration.ofDays(days);
    }

    // ---- the sweep ----

    @Scheduled(fixedDelayString = "${bss.billing.collections-tick-ms:60000}")
    public void sweep() {
        if (!tickGuard.claim("collections", Duration.ofSeconds(60))) {
            return; // another replica walks the ladder — one step, never two
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    sweepTenant(tenant.getId());
                } catch (Exception e) {
                    log.warn("collections sweep failed for tenant {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("collections");
        }
    }

    public void sweepTenant(String tenantId) {
        DunningPolicy policy = policies
                .findFirstByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId).orElse(null);
        if (policy == null) {
            return; // no policy, no ladder — nothing statutory happens by accident
        }
        CountryStatutoryPack pack = CountryStatutoryPack.of(policy.getCountry());
        List<Step> steps = parseSteps(policy.getStepsJson());
        OffsetDateTime now = clock.now();
        Map<String, Overdue> byAccount = overdueByAccount(tenantId, policy, now);

        // accounts whose position cleared outside the settle hook still cure here
        for (CollectionCase c : cases.findByTenantId(tenantId)) {
            if (!byAccount.containsKey(c.getAccountId()) && beyondCurrent(c.getState())
                    && !CollectionCase.WRITTEN_OFF.equals(c.getState())) {
                try {
                    newTx.executeWithoutResult(tx -> cure(tenantId, policy, c, now));
                } catch (Exception oneCase) {
                    log.warn("collections: cure of {} skipped this pass — {}",
                            c.getAccountId(), oneCase.getMessage());
                }
            }
        }

        for (Map.Entry<String, Overdue> e : byAccount.entrySet()) {
            try {
                newTx.executeWithoutResult(tx -> sweepAccount(
                        tenantId, policy, pack, steps, e.getKey(), e.getValue(), now));
            } catch (Exception oneAccount) {
                log.warn("collections: account {} skipped this pass — {}",
                        e.getKey(), oneAccount.getMessage());
            }
        }
    }

    private void sweepAccount(String tenantId, DunningPolicy policy, CountryStatutoryPack pack,
            List<Step> steps, String accountId, Overdue overdue, OffsetDateTime now) {
        BigDecimal floor = policy.getEntryThreshold().max(pack.minActionableAmount());
        CollectionCase c = cases.findByTenantIdAndAccountId(tenantId, accountId).orElse(null);
        if (c == null) {
            if (overdue.total().compareTo(floor) < 0) {
                return; // below the actionable floor — the law says leave it be
            }
            c = new CollectionCase();
            c.setId(UUID.randomUUID().toString());
            c.setTenantId(tenantId);
            c.setAccountId(accountId);
            c.setState(CollectionCase.CURRENT);
            c.setCreatedAt(now);
        }
        if (CollectionCase.WRITTEN_OFF.equals(c.getState())) {
            return; // written off stays written off — a fresh debt reopens by hand
        }
        c.setOverdueValue(overdue.total());
        c.setCurrency(overdue.currency());
        c.setOldestDueAt(overdue.oldestDueAt());
        c.setLastUpdate(now);

        if (overdue.total().compareTo(floor) < 0) {
            // genuinely under the actionable floor — cure or leave alone
            if (beyondCurrent(c.getState())) {
                cure(tenantId, policy, c, now);
            } else {
                cases.save(c);
            }
            return;
        }
        BigDecimal actionable = overdue.total().subtract(
                c.getDisputeHoldValue() == null ? BigDecimal.ZERO : c.getDisputeHoldValue());
        if (actionable.compareTo(floor) < 0) {
            // only the CONTESTED slice keeps it above the floor: the ladder
            // freezes where it stands until the dispute resolves
            cases.save(c);
            return;
        }
        if (c.isHardshipHold()) {
            cases.save(c);
            return;
        }
        if (c.getPromiseDueAt() != null) {
            if (c.getPromiseDueAt().isAfter(now)) {
                cases.save(c); // the promise holds the ladder
                return;
            }
            // the promise passed unpaid — the ladder resumes
            events.publish("PromiseToPayBrokenEvent", "promiseToPay", promiseView(c));
            c.setPromiseValue(null);
            c.setPromiseDueAt(null);
        }
        if (c.getStepIndex() >= steps.size()) {
            cases.save(c);
            return; // ladder exhausted — write-off is a human decision
        }
        Step step = steps.get(c.getStepIndex());
        if (now.isBefore(overdue.oldestDueAt().plus(span(step.offsetDays())))) {
            cases.save(c);
            return; // this rung's clock has not run yet
        }
        if (step.isEnforcement()) {
            // the statutory notice clock runs from the REAL demand+warning,
            // not from the policy's paper timeline
            if (c.getWarnedAt() == null
                    || now.isBefore(c.getWarnedAt().plus(span(pack.enforcementNoticeDays())))) {
                cases.save(c);
                return;
            }
        }
        BigDecimal feeCharged = maybeApplyFee(tenantId, pack, step, c, overdue, now);
        // SEAM (not wired): the legally required dunning letters could ride the
        // mailbox channel of the bill-distribution chain (BillChannelService /
        // BillDistributionService.letterOf) instead of only the event bus.
        switch (step.action()) {
            case "remind" -> c.setState(CollectionCase.REMINDED);
            case "warn" -> {
                c.setState(CollectionCase.WARNED);
                c.setWarnedAt(now);
            }
            case "restrict" -> {
                enforce(tenantId, c, "restrict");
                c.setState(CollectionCase.RESTRICTED);
                events.publish("ServiceRestrictedForNonPaymentEvent", "collectionCase", caseView(c));
            }
            case "suspend" -> {
                enforce(tenantId, c, "suspend");
                c.setState(CollectionCase.SUSPENDED);
                events.publish("ServiceSuspendedForNonPaymentEvent", "collectionCase", caseView(c));
            }
            case "terminate" -> c.setState(CollectionCase.TERMINATED);
            default -> throw new IllegalStateException("unknown step action " + step.action());
        }
        c.setStepIndex(c.getStepIndex() + 1);
        c.setLastUpdate(now);
        cases.save(c);
        Map<String, Object> event = caseView(c);
        event.put("step", stepView(step, feeCharged, c, pack));
        event.put("billNo", overdue.oldestBillNo());
        events.publish("DunningStepReachedEvent", "collectionCase", event);
        log.info("collections: account {} stepped to {} ({} overdue {})",
                accountId, c.getState(), overdue.total(), c.getCurrency());
    }

    /** The rung's fee, gated by the pack, attached to the oldest overdue bill
     * so paying that bill clears the fee with it. */
    private BigDecimal maybeApplyFee(String tenantId, CountryStatutoryPack pack, Step step,
            CollectionCase c, Overdue overdue, OffsetDateTime now) {
        if (!step.hasFee() || step.feeAmount() == null || step.feeAmount().signum() <= 0) {
            return null;
        }
        if (c.getFeeCount() >= pack.maxFeeBearingReminders()) {
            return null;
        }
        if (now.isBefore(overdue.oldestDueAt().plus(span(pack.reminderFeeGateDays())))) {
            return null; // the fee gate has not run — remind for free
        }
        BigDecimal amount = pack.reminderFeeCap() == null ? step.feeAmount()
                : step.feeAmount().min(pack.reminderFeeCap());
        CustomerBill bill = bills.findByIdAndTenantId(overdue.oldestBillId(), tenantId).orElse(null);
        if (bill == null) {
            return null;
        }
        AppliedBillingRate fee = new AppliedBillingRate();
        fee.setId(UUID.randomUUID().toString());
        fee.setTenantId(tenantId);
        fee.setName("Reminder fee (purregebyr)");
        fee.setRateType("reminderFee");
        fee.setAmountValue(amount);
        fee.setAmountUnit(bill.getAmountDueUnit());
        fee.setOwnerPartyId(c.getAccountId());
        fee.setBillId(bill.getId());
        fee.setRateDate(now);
        rates.save(fee);
        bill.setAmountDueValue(bill.getAmountDueValue().add(amount));
        bill.setLastUpdate(now);
        bills.save(bill);
        c.setFeeCount(c.getFeeCount() + 1);
        return amount;
    }

    /** Restrict or suspend every running service on the account; the ids are
     * recorded on the case so a cure reinstates exactly this set. Fail-closed:
     * an unreachable SOM leaves the case where it was. */
    private void enforce(String tenantId, CollectionCase c, String mode) {
        List<String> enforced = new ArrayList<>(readEnforced(c));
        for (Map<String, Object> service : som.servicesOf(c.getAccountId())) {
            String serviceId = String.valueOf(service.get("id"));
            if (!"active".equals(service.get("state"))) {
                continue;
            }
            if ("restrict".equals(mode)) {
                // emergency numbers stay reachable — the whitelist is not optional
                som.restrict(serviceId, "nonpayment", Map.of(
                        "outgoingBarred", true, "dataThrottled", true, "emergencyWhitelist", true));
            } else {
                som.suspend(serviceId, "nonpayment");
            }
            if (!enforced.contains(serviceId)) {
                enforced.add(serviceId);
            }
        }
        c.setEnforcedServicesJson(writeJson(enforced));
    }

    /** CURED: the actionable balance cleared, from any state — reinstate what
     * we took, charge the (contractual) reconnection fee, reset the ladder. */
    private void cure(String tenantId, DunningPolicy policy, CollectionCase c, OffsetDateTime now) {
        boolean suspended = CollectionCase.SUSPENDED.equals(c.getState());
        for (String serviceId : readEnforced(c)) {
            try {
                if (suspended) {
                    som.resume(serviceId);
                } else {
                    som.unrestrict(serviceId);
                }
            } catch (Exception e) {
                // reinstate what we can; the next sweep retries nothing here —
                // an operator sees the cured case and the failed service in the log
                log.warn("collections: reinstating service {} failed — {}", serviceId, e.getMessage());
            }
        }
        if (suspended && policy.getReconnectionFee() != null
                && policy.getReconnectionFee().signum() > 0) {
            // an unbilled standalone line — the next billing run carries it
            AppliedBillingRate fee = new AppliedBillingRate();
            fee.setId(UUID.randomUUID().toString());
            fee.setTenantId(tenantId);
            fee.setName("Reconnection after nonpayment suspension");
            fee.setRateType("reconnectionFee");
            fee.setAmountValue(policy.getReconnectionFee());
            fee.setAmountUnit(c.getCurrency() == null ? policy.getCurrency() : c.getCurrency());
            fee.setOwnerPartyId(c.getAccountId());
            fee.setRateDate(now);
            rates.save(fee);
        }
        if (c.getPromiseDueAt() != null) {
            events.publish("PromiseToPayKeptEvent", "promiseToPay", promiseView(c));
        }
        c.setState(CollectionCase.CURRENT);
        c.setStepIndex(0);
        c.setFeeCount(0);
        c.setWarnedAt(null);
        c.setPromiseValue(null);
        c.setPromiseDueAt(null);
        c.setEnforcedServicesJson(null);
        c.setOverdueValue(BigDecimal.ZERO);
        c.setOldestDueAt(null);
        c.setCuredAt(now);
        c.setLastUpdate(now);
        cases.save(c);
        events.publish("CollectionCuredEvent", "collectionCase", caseView(c));
        log.info("collections: account {} CURED — services reinstated, ladder reset", c.getAccountId());
    }

    /** The settle/installment paths call this in the same transaction the
     * money landed in — a payment that clears the balance cures immediately,
     * not on the next sweep. */
    public void onSettlement(String tenantId, String accountId) {
        CollectionCase c = cases.findByTenantIdAndAccountId(tenantId, accountId).orElse(null);
        if (c == null || !beyondCurrent(c.getState())
                || CollectionCase.WRITTEN_OFF.equals(c.getState())) {
            return;
        }
        DunningPolicy policy = policies
                .findFirstByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId).orElse(null);
        if (policy == null) {
            return;
        }
        OffsetDateTime now = clock.now();
        Overdue remaining = overdueByAccount(tenantId, policy, now).get(accountId);
        BigDecimal floor = policy.getEntryThreshold()
                .max(CountryStatutoryPack.of(policy.getCountry()).minActionableAmount());
        if (remaining == null || remaining.total().compareTo(floor) < 0) {
            cure(tenantId, policy, c, now);
        } else {
            c.setOverdueValue(remaining.total());
            c.setLastUpdate(now);
            cases.save(c);
        }
    }

    // ---- the aggregation ----

    /** Every account's overdue position: unpaid bills past due plus broken or
     * late installment plans (the plan's remainder feeds the same case).
     * Disputed bills are excluded whole — collection never chases contested
     * money; staff-set amount-scoped holds subtract at the case level. */
    private Map<String, Overdue> overdueByAccount(String tenantId, DunningPolicy policy,
            OffsetDateTime now) {
        Map<String, Overdue> byAccount = new LinkedHashMap<>();
        for (CustomerBill bill : bills.findByTenantIdAndStateIn(tenantId,
                List.of(CustomerBill.NEW, CustomerBill.PARTIALLY_PAID))) {
            OffsetDateTime due = bill.getBillDate().plus(span(policy.getPaymentTermDays()));
            BigDecimal amount = null;
            InstallmentPlan plan = plans.findByTenantIdAndBillId(tenantId, bill.getId()).orElse(null);
            if (plan != null && InstallmentPlan.BROKEN.equals(plan.getStatus())) {
                // the accelerated remainder feeds the same case — no separate
                // one-reminder dead end any more
                amount = plan.remainingOf(bill.getAmountDueValue());
            } else if (plan != null && InstallmentPlan.ACTIVE.equals(plan.getStatus())) {
                // a bill mid-plan ages by the PLAN's schedule, not the bill's
                if (plan.getNextDueAt() != null && plan.getNextDueAt().isBefore(now)) {
                    amount = plan.remainingOf(bill.getAmountDueValue());
                    due = plan.getNextDueAt();
                }
            } else if (due.isBefore(now)) {
                amount = bill.getAmountDueValue();
            }
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            if (disputeService.hasOpenDispute(tenantId, bill.getId())) {
                continue;
            }
            String account = bill.getOwnerPartyId();
            Overdue sofar = byAccount.get(account);
            if (sofar == null || due.isBefore(sofar.oldestDueAt())) {
                byAccount.put(account, new Overdue(
                        (sofar == null ? BigDecimal.ZERO : sofar.total()).add(amount),
                        due, bill.getAmountDueUnit(), bill.getId(), bill.getBillNo()));
            } else {
                byAccount.put(account, new Overdue(sofar.total().add(amount),
                        sofar.oldestDueAt(), sofar.currency(),
                        sofar.oldestBillId(), sofar.oldestBillNo()));
            }
        }
        return byAccount;
    }

    // ---- the faces ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findCases(String state) {
        String tenantId = tenantScope.currentTenantId();
        java.util.Optional<String> own = partyScope.scopedPartyId();
        if (own.isPresent()) {
            return cases.findByTenantIdAndAccountId(tenantId, own.get())
                    .map(c -> List.of(caseView(c))).orElse(List.of());
        }
        List<CollectionCase> rows = state == null ? cases.findByTenantId(tenantId)
                : cases.findByTenantIdAndState(tenantId, state);
        return rows.stream().map(this::caseView).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findCase(String id) {
        return caseView(requireOwnCase(id));
    }

    @Transactional
    public Map<String, Object> promiseToPay(String caseId, Map<String, Object> dto) {
        CollectionCase c = requireOwnCase(caseId);
        if (CollectionCase.WRITTEN_OFF.equals(c.getState())
                || CollectionCase.TERMINATED.equals(c.getState())) {
            throw new ConflictException("this case is " + c.getState() + " — no promises left to make");
        }
        if (c.getPromiseDueAt() != null && c.getPromiseDueAt().isAfter(clock.now())) {
            throw new ConflictException("a promise to pay is already standing");
        }
        DunningPolicy policy = requireActivePolicy(c.getTenantId());
        OffsetDateTime now = clock.now();
        // the allowance: so many promises per rolling window, so many days each
        if (c.getPromiseWindowStart() == null
                || c.getPromiseWindowStart().plus(span(policy.getPromisePeriodDays())).isBefore(now)) {
            c.setPromiseWindowStart(now);
            c.setPromisesMade(0);
        }
        if (c.getPromisesMade() >= policy.getPromiseMaxPerPeriod()) {
            throw new ConflictException("the promise-to-pay allowance ("
                    + policy.getPromiseMaxPerPeriod() + " per period) is used up");
        }
        int days = dto.get("days") == null ? policy.getPromiseMaxDays()
                : Integer.parseInt(String.valueOf(dto.get("days")));
        if (days < 1 || days > policy.getPromiseMaxDays()) {
            throw new BadRequestException("a promise can run 1-" + policy.getPromiseMaxDays() + " days");
        }
        BigDecimal amount = dto.get("amount") == null ? c.getOverdueValue()
                : new BigDecimal(String.valueOf(dto.get("amount")));
        if (amount.signum() <= 0) {
            throw new BadRequestException("the promised amount must be positive");
        }
        c.setPromiseValue(amount);
        c.setPromiseDueAt(now.plus(span(days)));
        c.setPromisesMade(c.getPromisesMade() + 1);
        c.setLastUpdate(now);
        cases.save(c);
        events.publish("PromiseToPayCreatedEvent", "promiseToPay", promiseView(c));
        return caseView(c);
    }

    /** Staff holds: an amount-scoped dispute freeze (the rest still ages) or
     * a manual hardship hold. */
    @Transactional
    public Map<String, Object> hold(String caseId, Map<String, Object> dto) {
        CollectionCase c = requireCase(caseId);
        String type = String.valueOf(dto.getOrDefault("type", ""));
        if ("dispute".equals(type)) {
            BigDecimal amount = dto.get("amount") == null ? null
                    : new BigDecimal(String.valueOf(dto.get("amount")));
            if (amount == null || amount.signum() <= 0) {
                throw new BadRequestException("a dispute hold freezes an AMOUNT — name it");
            }
            c.setDisputeHoldValue(amount);
        } else if ("hardship".equals(type)) {
            c.setHardshipHold(true);
        } else {
            throw new BadRequestException("hold type must be dispute or hardship");
        }
        c.setLastUpdate(OffsetDateTime.now());
        return caseView(cases.save(c));
    }

    @Transactional
    public Map<String, Object> release(String caseId, Map<String, Object> dto) {
        CollectionCase c = requireCase(caseId);
        String type = String.valueOf(dto.getOrDefault("type", ""));
        if ("dispute".equals(type)) {
            c.setDisputeHoldValue(null);
        } else if ("hardship".equals(type)) {
            c.setHardshipHold(false);
        } else {
            throw new BadRequestException("hold type must be dispute or hardship");
        }
        c.setLastUpdate(OffsetDateTime.now());
        return caseView(cases.save(c));
    }

    /** WRITE-OFF, the human decision at the ladder's end: only under the
     * policy threshold, only with a reason, only billing:admin (the route). */
    @Transactional
    public Map<String, Object> writeOff(String caseId, Map<String, Object> dto) {
        CollectionCase c = requireCase(caseId);
        if (CollectionCase.WRITTEN_OFF.equals(c.getState())) {
            throw new ConflictException("this case is already written off");
        }
        String reason = dto.get("reason") == null ? null : String.valueOf(dto.get("reason")).trim();
        if (reason == null || reason.isEmpty()) {
            throw new BadRequestException("a write-off needs a reason — the auditors will ask");
        }
        DunningPolicy policy = requireActivePolicy(c.getTenantId());
        if (policy.getWriteOffThreshold().signum() > 0
                && c.getOverdueValue().compareTo(policy.getWriteOffThreshold()) > 0) {
            throw new ConflictException("the balance " + c.getOverdueValue()
                    + " is above the write-off threshold " + policy.getWriteOffThreshold());
        }
        OffsetDateTime now = clock.now();
        for (CustomerBill bill : bills.findByTenantIdAndStateIn(c.getTenantId(),
                List.of(CustomerBill.NEW, CustomerBill.PARTIALLY_PAID))) {
            if (c.getAccountId().equals(bill.getOwnerPartyId())) {
                bill.setState(CustomerBill.WRITTEN_OFF);
                bill.setLastUpdate(now);
                bills.save(bill);
            }
        }
        c.setState(CollectionCase.WRITTEN_OFF);
        c.setWrittenOffAt(now);
        c.setWriteOffReason(reason);
        c.setLastUpdate(now);
        cases.save(c);
        Map<String, Object> view = caseView(c);
        events.publish("DebtWrittenOffEvent", "collectionCase", view);
        log.info("collections: account {} written off ({} {}): {}",
                c.getAccountId(), c.getOverdueValue(), c.getCurrency(), reason);
        return view;
    }

    // ---- policy CRUD ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findPolicies() {
        return policies.findByTenantId(tenantScope.currentTenantId()).stream()
                .map(this::policyView).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findPolicy(String id) {
        return policyView(policies.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("DunningPolicy", id)));
    }

    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> dto) {
        DunningPolicy policy = new DunningPolicy();
        policy.setId(UUID.randomUUID().toString());
        policy.setTenantId(tenantScope.currentTenantId());
        policy.setCreatedAt(OffsetDateTime.now());
        apply(policy, dto);
        return policyView(policies.save(policy));
    }

    @Transactional
    public Map<String, Object> patchPolicy(String id, Map<String, Object> dto) {
        DunningPolicy policy = policies.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("DunningPolicy", id));
        apply(policy, dto);
        return policyView(policies.save(policy));
    }

    /** Merge + validate: the statutory pack has the last word, always on the
     * MERGED result, so no sequence of patches sneaks under the floor. */
    private void apply(DunningPolicy policy, Map<String, Object> dto) {
        if (dto.get("name") != null) {
            policy.setName(String.valueOf(dto.get("name")));
        }
        if (policy.getName() == null || policy.getName().isBlank()) {
            throw new BadRequestException("a policy needs a name");
        }
        if (dto.get("country") != null) {
            policy.setCountry(String.valueOf(dto.get("country")).toUpperCase());
        }
        if (policy.getCountry() == null) {
            policy.setCountry("NO");
        }
        if (dto.get("paymentTermDays") != null) {
            policy.setPaymentTermDays(Integer.parseInt(String.valueOf(dto.get("paymentTermDays"))));
        }
        if (dto.get("entryThreshold") != null) {
            policy.setEntryThreshold(new BigDecimal(String.valueOf(dto.get("entryThreshold"))));
        }
        if (dto.get("currency") != null) {
            policy.setCurrency(String.valueOf(dto.get("currency")));
        }
        if (dto.get("steps") != null) {
            policy.setStepsJson(writeJson(dto.get("steps")));
        }
        if (policy.getStepsJson() == null) {
            throw new BadRequestException("a policy needs steps — the ladder itself");
        }
        if (dto.get("reconnectionFee") != null) {
            policy.setReconnectionFee(new BigDecimal(String.valueOf(dto.get("reconnectionFee"))));
        }
        if (dto.get("writeOffThreshold") != null) {
            policy.setWriteOffThreshold(new BigDecimal(String.valueOf(dto.get("writeOffThreshold"))));
        }
        if (dto.get("promiseMaxPerPeriod") != null) {
            policy.setPromiseMaxPerPeriod(Integer.parseInt(String.valueOf(dto.get("promiseMaxPerPeriod"))));
        }
        if (dto.get("promisePeriodDays") != null) {
            policy.setPromisePeriodDays(Integer.parseInt(String.valueOf(dto.get("promisePeriodDays"))));
        }
        if (dto.get("promiseMaxDays") != null) {
            policy.setPromiseMaxDays(Integer.parseInt(String.valueOf(dto.get("promiseMaxDays"))));
        }
        if (dto.get("autoRefundThreshold") != null) {
            policy.setAutoRefundThreshold(new BigDecimal(String.valueOf(dto.get("autoRefundThreshold"))));
        }
        if (dto.get("active") != null) {
            policy.setActive(Boolean.parseBoolean(String.valueOf(dto.get("active"))));
        }
        List<Step> steps = parseSteps(policy.getStepsJson());
        if (steps.isEmpty()) {
            throw new BadRequestException("a policy needs at least one step");
        }
        CountryStatutoryPack.of(policy.getCountry()).validate(steps, policy.getEntryThreshold());
        policy.setLastUpdate(OffsetDateTime.now());
    }

    private Map<String, Object> policyView(DunningPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("country", p.getCountry());
        m.put("paymentTermDays", p.getPaymentTermDays());
        m.put("entryThreshold", p.getEntryThreshold());
        if (p.getCurrency() != null) {
            m.put("currency", p.getCurrency());
        }
        try {
            m.put("steps", json.readValue(p.getStepsJson(), Object.class));
        } catch (Exception e) {
            m.put("steps", List.of());
        }
        m.put("reconnectionFee", p.getReconnectionFee());
        m.put("writeOffThreshold", p.getWriteOffThreshold());
        m.put("promiseMaxPerPeriod", p.getPromiseMaxPerPeriod());
        m.put("promisePeriodDays", p.getPromisePeriodDays());
        m.put("promiseMaxDays", p.getPromiseMaxDays());
        m.put("autoRefundThreshold", p.getAutoRefundThreshold());
        m.put("active", p.isActive());
        // the floor the tenant cannot undercut, read-only beside the editor
        m.put("statutory", CountryStatutoryPack.of(p.getCountry()).view());
        m.put("@type", "DunningPolicy");
        return m;
    }

    // ---- plumbing ----

    private boolean beyondCurrent(String state) {
        return !CollectionCase.CURRENT.equals(state);
    }

    private CollectionCase requireCase(String id) {
        return cases.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CollectionCase", id));
    }

    /** Scoped tokens address only their own case; a foreign id is a 404. */
    private CollectionCase requireOwnCase(String id) {
        CollectionCase c = requireCase(id);
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(c.getAccountId())) {
                throw NotFoundException.forResource("CollectionCase", id);
            }
        });
        return c;
    }

    private DunningPolicy requireActivePolicy(String tenantId) {
        return policies.findFirstByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId)
                .orElseThrow(() -> new ConflictException("no active dunning policy for this tenant"));
    }

    List<Step> parseSteps(String stepsJson) {
        try {
            List<Map<String, Object>> raw = json.readValue(stepsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
            List<Step> steps = new ArrayList<>();
            for (Map<String, Object> s : raw) {
                if (s.get("offsetDays") == null || s.get("action") == null) {
                    throw new BadRequestException("every step needs offsetDays and action");
                }
                String action = String.valueOf(s.get("action"));
                if (!List.of("remind", "warn", "restrict", "suspend", "terminate").contains(action)) {
                    throw new BadRequestException("step action must be remind, warn, restrict,"
                            + " suspend or terminate");
                }
                steps.add(new Step(
                        Integer.parseInt(String.valueOf(s.get("offsetDays"))),
                        action,
                        s.get("templateId") == null ? null : String.valueOf(s.get("templateId")),
                        s.get("feeType") == null ? "none" : String.valueOf(s.get("feeType")),
                        s.get("feeAmount") == null ? null
                                : new BigDecimal(String.valueOf(s.get("feeAmount")))));
            }
            return steps;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("steps must be a JSON array of"
                    + " {offsetDays, action, templateId, feeType, feeAmount}");
        }
    }

    private Map<String, Object> caseView(CollectionCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("accountId", c.getAccountId());
        m.put("state", c.getState());
        m.put("overdueBalance", Map.of(
                "unit", c.getCurrency() == null ? "" : c.getCurrency(),
                "value", c.getOverdueValue()));
        if (c.getOldestDueAt() != null) {
            m.put("oldestDueAt", c.getOldestDueAt().toString());
        }
        m.put("stepIndex", c.getStepIndex());
        m.put("feeCount", c.getFeeCount());
        if (c.getWarnedAt() != null) {
            m.put("warnedAt", c.getWarnedAt().toString());
        }
        Map<String, Object> holds = new LinkedHashMap<>();
        if (c.getPromiseDueAt() != null) {
            holds.put("promiseToPay", Map.of(
                    "amount", c.getPromiseValue(), "dueAt", c.getPromiseDueAt().toString()));
        }
        if (c.getDisputeHoldValue() != null) {
            holds.put("dispute", Map.of("amount", c.getDisputeHoldValue()));
        }
        if (c.isHardshipHold()) {
            holds.put("hardship", true);
        }
        m.put("holds", holds);
        m.put("enforcedServices", readEnforced(c));
        if (c.getCuredAt() != null) {
            m.put("curedAt", c.getCuredAt().toString());
        }
        if (c.getWrittenOffAt() != null) {
            m.put("writtenOffAt", c.getWrittenOffAt().toString());
            m.put("writeOffReason", c.getWriteOffReason());
        }
        m.put("relatedParty", List.of(Map.of("id", c.getAccountId(), "role", "customer")));
        m.put("@type", "CollectionCase");
        return m;
    }

    private Map<String, Object> promiseView(CollectionCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", c.getId());
        m.put("accountId", c.getAccountId());
        m.put("amount", c.getPromiseValue());
        m.put("dueAt", c.getPromiseDueAt() == null ? null : c.getPromiseDueAt().toString());
        m.put("currency", c.getCurrency());
        m.put("relatedParty", List.of(Map.of("id", c.getAccountId(), "role", "customer")));
        m.put("@type", "PromiseToPay");
        return m;
    }

    private Map<String, Object> stepView(Step step, BigDecimal feeCharged,
            CollectionCase c, CountryStatutoryPack pack) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", step.action());
        m.put("offsetDays", step.offsetDays());
        if (step.templateId() != null) {
            m.put("templateId", step.templateId());
        }
        if (feeCharged != null) {
            m.put("feeCharged", feeCharged);
        }
        if ("warn".equals(step.action()) && c.getWarnedAt() != null) {
            // the advance warning must name the real date enforcement CAN start
            m.put("enforceableAt", c.getWarnedAt().plus(span(pack.enforcementNoticeDays())).toString());
        }
        return m;
    }

    private List<String> readEnforced(CollectionCase c) {
        if (c.getEnforcedServicesJson() == null) {
            return List.of();
        }
        try {
            return json.readValue(c.getEnforcedServicesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON", e);
        }
    }
}
