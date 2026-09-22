package com.bss.billing.service;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.api.OffsetPageRequest;
import com.bss.billing.api.PagedResult;
import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.AppliedBillingRateView;
import com.bss.billing.dto.AttachmentRef;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.dto.DisputeChip;
import com.bss.billing.dto.EntityRef;
import com.bss.billing.dto.InstallmentPaymentRequest;
import com.bss.billing.dto.InstallmentPlanEvent;
import com.bss.billing.dto.InstallmentPlanRequest;
import com.bss.billing.dto.InstallmentPlanView;
import com.bss.billing.dto.Money;
import com.bss.billing.dto.MoneyDto;
import com.bss.billing.dto.PaymentRef;
import com.bss.billing.dto.RelatedPartyRef;
import com.bss.billing.dto.TimePeriod;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.exception.ConflictException;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CustomerBillService {

    private static final String RESOURCE = "CustomerBill";
    private static final TypeReference<List<PaymentRef>> PAYMENT_REFS = new TypeReference<>() {
    };

    private final CustomerBillRepository repository;
    private final AppliedBillingRateRepository rateRepository;
    private final DownstreamClients.PaymentClient paymentClient;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    private final com.bss.billing.repository.CustomerBillOnDemandRepository onDemandRepository;
    private final com.bss.billing.repository.InstallmentPlanRepository plans;
    private final com.bss.billing.repository.BillDisputeRepository disputeChips;
    // lazy: collections listens to settlements, settlements never call back
    private final org.springframework.beans.factory.ObjectProvider<CollectionService> collections;

    public CustomerBillService(CustomerBillRepository repository, AppliedBillingRateRepository rateRepository,
            com.bss.billing.repository.CustomerBillOnDemandRepository onDemandRepository,
            DownstreamClients.PaymentClient paymentClient, DomainEventPublisher events, PartyScope partyScope,
            TenantScope tenantScope, ObjectMapper objectMapper,
            com.bss.billing.repository.InstallmentPlanRepository plans,
            com.bss.billing.repository.BillDisputeRepository disputeChips,
            org.springframework.beans.factory.ObjectProvider<CollectionService> collections) {
        this.repository = repository;
        this.rateRepository = rateRepository;
        this.onDemandRepository = onDemandRepository;
        this.plans = plans;
        this.disputeChips = disputeChips;
        this.collections = collections;
        this.paymentClient = paymentClient;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    // ---- TMF678 CustomerBillOnDemand resource ----

    /**
     * The request is the caller's document, kept VERBATIM (the open edge):
     * the store holds it as sent, the view is that tree with the server's
     * fields written over it.
     */
    @Transactional
    public ObjectNode createOnDemand(JsonNode body) {
        com.bss.billing.entity.CustomerBillOnDemand e = new com.bss.billing.entity.CustomerBillOnDemand();
        String id = java.util.UUID.randomUUID().toString();
        e.setId(id);
        e.setHref(ApiConstants.BASE_PATH + "/customerBillOnDemand/" + id);
        e.setTenantId(tenantScope.currentTenantId());
        JsonNode state = body == null ? null : body.get("state");
        e.setState(state == null || state.isNull() ? "done" : state.asText());
        e.setPayloadJson(writeJsonValue(body));
        e.setCreatedAt(OffsetDateTime.now());
        e.setLastUpdate(OffsetDateTime.now());
        return onDemandView(onDemandRepository.save(e));
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> findOnDemand(Map<String, String> filters) {
        return onDemandRepository.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(o -> filters.get("id") == null || filters.get("id").equals(o.getId()))
                .filter(o -> filters.get("href") == null || filters.get("href").equals(o.getHref()))
                .map(this::onDemandView).toList();
    }

    @Transactional(readOnly = true)
    public ObjectNode findOnDemandById(String id) {
        return onDemandView(onDemandRepository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CustomerBillOnDemand", id)));
    }

    private ObjectNode onDemandView(com.bss.billing.entity.CustomerBillOnDemand e) {
        ObjectNode view = objectMapper.createObjectNode();
        try {
            JsonNode stored = e.getPayloadJson() == null ? null : objectMapper.readTree(e.getPayloadJson());
            if (stored instanceof ObjectNode o) {
                view.setAll(o);
            }
        } catch (Exception ignored) {
            // fall through with server fields only
        }
        view.put("id", e.getId());
        view.put("href", e.getHref());
        view.put("state", e.getState());
        if (!view.has("billDocument")) {
            view.putArray("billDocument");
        }
        view.put("@type", "CustomerBillOnDemand");
        return view;
    }

    private String writeJsonValue(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public PagedResult<CustomerBillDto> findAll(int offset, int limit, Map<String, String> filters) {
        CustomerBill probe = probeFor(filters);
        probe.setTenantId(tenantScope.currentTenantId());
        // Customers see their own bills only, whatever else they filter on.
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<CustomerBill> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toDto).toList(), page.getTotalElements());
    }

    private CustomerBill probeFor(Map<String, String> filters) {
        CustomerBill probe = new CustomerBill();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "href" -> probe.setHref(f.getValue());
                case "state" -> probe.setState(f.getValue());
                case "billNo" -> probe.setBillNo(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                case "fields", "sort" -> { }
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public CustomerBillDto findById(String id) {
        CustomerBill entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<AppliedBillingRateView> ratesOf(String billId) {
        String tenantId = tenantScope.currentTenantId();
        CustomerBill bill = repository.findByIdAndTenantId(billId, tenantId)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, billId));
        requireOwn(bill);
        return rateRepository.findByTenantIdAndBillId(tenantId, billId).stream().map(this::rateView).toList();
    }

    /**
     * The one legal change: settling a new bill with an authorized payment
     * covering the amount due — the payment is captured in the same breath.
     */
    @Transactional
    public CustomerBillDto settle(String id, CustomerBillDto patch) {
        CustomerBill entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        // Settling ('settled') keeps its guarantee: an authorized payment covering
        // the amount, captured atomically. Any other attribute change (state,
        // notes) is a plain TMF PATCH and just applies.
        if (!CustomerBill.SETTLED.equals(patch.getState())) {
            if (patch.getState() != null) {
                entity.setState(patch.getState());
            }
            entity.setLastUpdate(OffsetDateTime.now());
            CustomerBillDto updated = toDto(repository.save(entity));
            events.publish("CustomerBillAttributeValueChangeEvent", "customerBill", updated);
            return updated;
        }
        // a bill mid-plan settles ONLY through the plan — unless the plan
        // BROKE, in which case the remaining balance is due at once and this
        // is exactly the door it comes through
        com.bss.billing.entity.InstallmentPlan plan = plans
                .findByTenantIdAndBillId(entity.getTenantId(), entity.getId()).orElse(null);
        boolean brokenPlan = plan != null
                && com.bss.billing.entity.InstallmentPlan.BROKEN.equals(plan.getStatus());
        if (!CustomerBill.NEW.equals(entity.getState())
                && !(CustomerBill.PARTIALLY_PAID.equals(entity.getState()) && brokenPlan)) {
            throw new ConflictException("bill is '" + entity.getState() + "' and cannot be settled again");
        }
        String paymentId = patch.paymentId();
        if (paymentId == null) {
            throw new BadRequestException("settling a bill requires a payment reference");
        }
        java.math.BigDecimal owed = brokenPlan
                ? plan.remainingOf(entity.getAmountDueValue()) : entity.getAmountDueValue();
        String problem = paymentClient.validateAuthorized(paymentId, entity.getOwnerPartyId(), owed);
        if (!problem.isEmpty()) {
            throw new ConflictException(problem);
        }
        paymentClient.capture(paymentId);

        entity.setState(CustomerBill.SETTLED);
        entity.setPaymentJson(writeJsonArray(patch.getPayment()));
        entity.setLastUpdate(OffsetDateTime.now());
        CustomerBillDto updated = toDto(repository.save(entity));
        events.publish("CustomerBillStateChangeEvent", "customerBill", updated);
        // the settlement is collections' cure signal — same transaction, so a
        // payment that clears the balance reinstates services immediately
        collections.ifAvailable(c -> c.onSettlement(entity.getTenantId(), entity.getOwnerPartyId()));
        return updated;
    }

    /**
     * PAY IN PARTS: split an unpaid bill into 2-12 equal monthly
     * installments (the last takes the rounding remainder). One plan per
     * bill; the customer is told the terms in plain numbers.
     */
    @Transactional
    public InstallmentPlanView createInstallmentPlan(String billId, InstallmentPlanRequest dto) {
        CustomerBill bill = repository.findByIdAndTenantId(billId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, billId));
        requireOwn(bill);
        if (!CustomerBill.NEW.equals(bill.getState())) {
            throw new ConflictException("only an unpaid bill can be split (state: " + bill.getState() + ")");
        }
        if (plans.findByTenantIdAndBillId(bill.getTenantId(), billId).isPresent()) {
            throw new ConflictException("this bill already has an installment plan");
        }
        int n = dto.installments() == null ? 3 : dto.installments();
        if (n < 2 || n > 12) {
            throw new BadRequestException("installments must be 2-12");
        }
        com.bss.billing.entity.InstallmentPlan plan = new com.bss.billing.entity.InstallmentPlan();
        plan.setId(java.util.UUID.randomUUID().toString());
        plan.setTenantId(bill.getTenantId());
        plan.setBillId(billId);
        plan.setInstallments(n);
        plan.setAmountPer(bill.getAmountDueValue()
                .divide(java.math.BigDecimal.valueOf(n), 2, java.math.RoundingMode.DOWN));
        plan.setCurrency(bill.getAmountDueUnit());
        plan.setPaidCount(0);
        plan.setStatus(com.bss.billing.entity.InstallmentPlan.ACTIVE);
        // operators align the first part to payday; default one month out
        OffsetDateTime firstDue = OffsetDateTime.now().plusMonths(1);
        if (dto.firstDueAt() != null) {
            try {
                firstDue = OffsetDateTime.parse(dto.firstDueAt());
            } catch (Exception e) {
                throw new BadRequestException("firstDueAt must be an ISO date-time");
            }
            if (firstDue.isAfter(OffsetDateTime.now().plusMonths(2))) {
                throw new BadRequestException("the first part must be due within two months");
            }
        }
        plan.setNextDueAt(firstDue);
        plan.setCreatedAt(OffsetDateTime.now());
        plan.setLastUpdate(OffsetDateTime.now());
        plans.save(plan);
        InstallmentPlanView view = planView(plan, bill);
        events.publish("InstallmentPlanCreatedEvent", "installmentPlan", new InstallmentPlanEvent(
                view, bill.getBillNo(), null, List.of(RelatedPartyRef.customer(bill.getOwnerPartyId()))));
        return view;
    }

    /** One part lands: an authorized payment covering THIS installment is
     * captured; the last part settles the bill itself. */
    @Transactional
    public InstallmentPlanView payInstallment(String billId, InstallmentPaymentRequest dto) {
        CustomerBill bill = repository.findByIdAndTenantId(billId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, billId));
        requireOwn(bill);
        com.bss.billing.entity.InstallmentPlan plan = plans
                .findByTenantIdAndBillId(bill.getTenantId(), billId)
                .filter(p -> com.bss.billing.entity.InstallmentPlan.ACTIVE.equals(p.getStatus()))
                .orElseThrow(() -> new BadRequestException("this bill has no active installment plan"));
        String paymentId = dto == null ? null : dto.paymentId();
        if (paymentId == null) {
            throw new BadRequestException("an installment needs a payment reference");
        }
        java.math.BigDecimal due = plan.amountOf(plan.getPaidCount(), bill.getAmountDueValue());
        String problem = paymentClient.validateAuthorized(paymentId, bill.getOwnerPartyId(), due);
        if (!problem.isEmpty()) {
            throw new ConflictException(problem);
        }
        paymentClient.capture(paymentId);
        plan.setPaidCount(plan.getPaidCount() + 1);
        plan.setRemindedAt(null); // a payment resets the dunning clock
        boolean done = plan.getPaidCount() >= plan.getInstallments();
        plan.setStatus(done ? com.bss.billing.entity.InstallmentPlan.COMPLETED
                : com.bss.billing.entity.InstallmentPlan.ACTIVE);
        plan.setNextDueAt(done ? null : OffsetDateTime.now().plusMonths(1));
        plan.setLastUpdate(OffsetDateTime.now());
        plans.save(plan);
        bill.setState(done ? CustomerBill.SETTLED : CustomerBill.PARTIALLY_PAID);
        bill.setLastUpdate(OffsetDateTime.now());
        CustomerBillDto updated = toDto(repository.save(bill));
        InstallmentPlanView view = planView(plan, bill);
        events.publish("InstallmentPaidEvent", "installmentPlan", new InstallmentPlanEvent(
                view, bill.getBillNo(), due, List.of(RelatedPartyRef.customer(bill.getOwnerPartyId()))));
        if (done) {
            events.publish("CustomerBillStateChangeEvent", "customerBill", updated);
        }
        // an installment landing can be the payment that cures the case
        collections.ifAvailable(c -> c.onSettlement(bill.getTenantId(), bill.getOwnerPartyId()));
        return view;
    }

    /** The plan as both surfaces read it. */
    private InstallmentPlanView planView(com.bss.billing.entity.InstallmentPlan plan, CustomerBill bill) {
        return new InstallmentPlanView(bill.getId(), plan.getInstallments(), plan.getPaidCount(),
                plan.getAmountPer(),
                plan.amountOf(plan.getInstallments() - 1, bill.getAmountDueValue()),
                plan.getPaidCount() < plan.getInstallments()
                        ? plan.amountOf(plan.getPaidCount(), bill.getAmountDueValue()) : null,
                plan.getCurrency(), plan.getStatus(),
                plan.getNextDueAt() == null ? null : plan.getNextDueAt().toString(),
                "InstallmentPlan");
    }

    /** Attach the plan (when one exists) to a bill DTO for the UIs. */
    public InstallmentPlanView planOf(String tenantId, String billId,
            java.math.BigDecimal total, String unit) {
        return plans.findByTenantIdAndBillId(tenantId, billId).map(plan -> {
            CustomerBill shim = new CustomerBill();
            shim.setId(billId);
            shim.setAmountDueValue(total);
            shim.setAmountDueUnit(unit);
            return planView(plan, shim);
        }).orElse(null);
    }

    /**
     * Scoped tokens address only their own bills; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(CustomerBill entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private CustomerBillDto toDto(CustomerBill entity) {
        CustomerBillDto dto = new CustomerBillDto();
        dto.setInstallmentPlan(planOf(entity.getTenantId(), entity.getId(),
                entity.getAmountDueValue(), entity.getAmountDueUnit()));
        disputeChips.findFirstByTenantIdAndBillIdOrderByCreatedAtDesc(
                entity.getTenantId(), entity.getId()).ifPresent(d ->
                dto.setDispute(new DisputeChip(d.getId(), d.getStatus(), d.getReason())));
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setBillNo(entity.getBillNo());
        dto.setState(entity.getState());
        dto.setAmountDue(new MoneyDto(entity.getAmountDueUnit(), entity.getAmountDueValue()));
        dto.setBillingPeriod(TimePeriod.ofDates(entity.getPeriodStart(), entity.getPeriodEnd()));
        dto.setRelatedParty(List.of(RelatedPartyRef.individual(entity.getOwnerPartyId())));
        // TMF678 requires a billingAccount (or financialAccount) reference.
        dto.setBillingAccount(EntityRef.billingAccount(entity.getOwnerPartyId() + "-account"));
        dto.setPayment(readPaymentRefs(entity.getPaymentJson()));
        // The bill's rendered document (TMF678 billDocument): one attachment
        // per bill, addressable so the customer can fetch the PDF.
        dto.setBillDocument(List.of(AttachmentRef.pdfOf(entity.getId(), entity.getBillNo(), entity.getHref())));
        dto.setDistributionChannel(entity.getDistributionChannel());
        dto.setBillDate(entity.getBillDate());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("CustomerBill");
        return dto;
    }

    private AppliedBillingRateView rateView(AppliedBillingRate rate) {
        return new AppliedBillingRateView(rate.getId(),
                ApiConstants.BASE_PATH + "/appliedCustomerBillingRate/" + rate.getId(),
                rate.getName(), "AppliedCustomerBillingRate", rate.getRateType(),
                // the unit as the line stores it (a missing unit has always read "null" here)
                new Money(String.valueOf(rate.getAmountUnit()), rate.getAmountValue()),
                // TMF678 appliedTax: only when the catalog price declared a rate for this line
                rate.getAppliedTaxRate() == null ? null
                        : List.of(new AppliedBillingRateView.AppliedTax("VAT", rate.getAppliedTaxRate())),
                // TMF678: a rate on a bill is billed; a standalone/unbilled rate is not.
                rate.getBillId() != null,
                // Consolidated org invoices: which member this line belongs to.
                rate.getOwnerPartyId() == null ? null : EntityRef.of(rate.getOwnerPartyId()),
                rate.getBillId() == null ? null : EntityRef.of(rate.getBillId()),
                String.valueOf(rate.getRateDate()));
    }

    /** Top-level TMF678 appliedCustomerBillingRate list, with an isBilled filter. */
    @Transactional(readOnly = true)
    public List<AppliedBillingRateView> findAllRates(Map<String, String> filters) {
        String tenantId = tenantScope.currentTenantId();
        return rateRepository.findByTenantId(tenantId).stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("isBilled") == null
                        || Boolean.parseBoolean(filters.get("isBilled")) == (r.getBillId() != null))
                .map(this::rateView).toList();
    }

    @Transactional(readOnly = true)
    public AppliedBillingRateView findRateById(String id) {
        AppliedBillingRate rate = rateRepository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("AppliedCustomerBillingRate", id));
        return rateView(rate);
    }

    private String writeJsonArray(List<PaymentRef> value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<PaymentRef> readPaymentRefs(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, PAYMENT_REFS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
