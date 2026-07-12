package com.bss.billing.service;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.api.OffsetPageRequest;
import com.bss.billing.api.PagedResult;
import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.dto.MoneyDto;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
    };

    private final CustomerBillRepository repository;
    private final AppliedBillingRateRepository rateRepository;
    private final DownstreamClients.PaymentClient paymentClient;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    private final com.bss.billing.repository.CustomerBillOnDemandRepository onDemandRepository;

    public CustomerBillService(CustomerBillRepository repository, AppliedBillingRateRepository rateRepository,
            com.bss.billing.repository.CustomerBillOnDemandRepository onDemandRepository,
            DownstreamClients.PaymentClient paymentClient, DomainEventPublisher events, PartyScope partyScope,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.repository = repository;
        this.rateRepository = rateRepository;
        this.onDemandRepository = onDemandRepository;
        this.paymentClient = paymentClient;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    // ---- TMF678 CustomerBillOnDemand resource ----

    @Transactional
    public Map<String, Object> createOnDemand(Map<String, Object> body) {
        com.bss.billing.entity.CustomerBillOnDemand e = new com.bss.billing.entity.CustomerBillOnDemand();
        String id = java.util.UUID.randomUUID().toString();
        e.setId(id);
        e.setHref(ApiConstants.BASE_PATH + "/customerBillOnDemand/" + id);
        e.setTenantId(tenantScope.currentTenantId());
        e.setState(body.get("state") == null ? "done" : String.valueOf(body.get("state")));
        e.setPayloadJson(writeJsonValue(body));
        e.setCreatedAt(OffsetDateTime.now());
        e.setLastUpdate(OffsetDateTime.now());
        return onDemandToMap(onDemandRepository.save(e));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOnDemand(Map<String, String> filters) {
        return onDemandRepository.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(o -> filters.get("id") == null || filters.get("id").equals(o.getId()))
                .filter(o -> filters.get("href") == null || filters.get("href").equals(o.getHref()))
                .map(this::onDemandToMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOnDemandById(String id) {
        return onDemandToMap(onDemandRepository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CustomerBillOnDemand", id)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onDemandToMap(com.bss.billing.entity.CustomerBillOnDemand e) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        try {
            Object stored = e.getPayloadJson() == null ? null
                    : objectMapper.readValue(e.getPayloadJson(), Object.class);
            if (stored instanceof Map<?, ?> m) {
                map.putAll((Map<String, Object>) m);
            }
        } catch (Exception ignored) {
            // fall through with server fields only
        }
        map.put("id", e.getId());
        map.put("href", e.getHref());
        map.put("state", e.getState());
        map.putIfAbsent("billDocument", java.util.List.of());
        map.put("@type", "CustomerBillOnDemand");
        return map;
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
    public List<Map<String, Object>> ratesOf(String billId) {
        String tenantId = tenantScope.currentTenantId();
        CustomerBill bill = repository.findByIdAndTenantId(billId, tenantId)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, billId));
        requireOwn(bill);
        return rateRepository.findByTenantIdAndBillId(tenantId, billId).stream().map(this::rateToMap).toList();
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
        if (!CustomerBill.NEW.equals(entity.getState())) {
            throw new ConflictException("bill is '" + entity.getState() + "' and cannot be settled again");
        }
        Object paymentId = patch.getPayment() == null || patch.getPayment().isEmpty()
                ? null : patch.getPayment().get(0).get("id");
        if (paymentId == null) {
            throw new BadRequestException("settling a bill requires a payment reference");
        }
        String problem = paymentClient.validateAuthorized(String.valueOf(paymentId),
                entity.getOwnerPartyId(), entity.getAmountDueValue());
        if (!problem.isEmpty()) {
            throw new ConflictException(problem);
        }
        paymentClient.capture(String.valueOf(paymentId));

        entity.setState(CustomerBill.SETTLED);
        entity.setPaymentJson(writeJsonArray(patch.getPayment()));
        entity.setLastUpdate(OffsetDateTime.now());
        CustomerBillDto updated = toDto(repository.save(entity));
        events.publish("CustomerBillStateChangeEvent", "customerBill", updated);
        return updated;
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
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setBillNo(entity.getBillNo());
        dto.setState(entity.getState());
        dto.setAmountDue(new MoneyDto(entity.getAmountDueUnit(), entity.getAmountDueValue()));
        dto.setBillingPeriod(Map.of(
                "startDateTime", entity.getPeriodStart().toString(),
                "endDateTime", entity.getPeriodEnd().toString()));
        dto.setRelatedParty(List.of(Map.of(
                "id", entity.getOwnerPartyId(), "role", "customer", "@referredType", "Individual")));
        // TMF678 requires a billingAccount (or financialAccount) reference.
        dto.setBillingAccount(Map.of(
                "id", entity.getOwnerPartyId() + "-account",
                "@referredType", "BillingAccount",
                "@type", "BillingAccountRef"));
        dto.setPayment(readJsonArray(entity.getPaymentJson()));
        // The bill's rendered document (TMF678 billDocument): one attachment
        // per bill, addressable so the customer can fetch the PDF.
        dto.setBillDocument(List.of(Map.of(
                "id", entity.getId() + "-document",
                "name", "Bill " + entity.getBillNo(),
                "@type", "AttachmentRefOrValue",
                "href", entity.getHref() + "/document")));
        dto.setBillDate(entity.getBillDate());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("CustomerBill");
        return dto;
    }

    private Map<String, Object> rateToMap(AppliedBillingRate rate) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", rate.getId());
        m.put("href", ApiConstants.BASE_PATH + "/appliedCustomerBillingRate/" + rate.getId());
        m.put("name", rate.getName());
        m.put("@type", "AppliedCustomerBillingRate");
        m.put("type", rate.getRateType());
        m.put("taxExcludedAmount", Map.of("unit", String.valueOf(rate.getAmountUnit()),
                "value", rate.getAmountValue()));
        // TMF678: a rate on a bill is billed; a standalone/unbilled rate is not.
        m.put("isBilled", rate.getBillId() != null);
        if (rate.getBillId() != null) {
            m.put("bill", Map.of("id", rate.getBillId()));
        }
        m.put("date", String.valueOf(rate.getRateDate()));
        return m;
    }

    /** Top-level TMF678 appliedCustomerBillingRate list, with an isBilled filter. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllRates(Map<String, String> filters) {
        String tenantId = tenantScope.currentTenantId();
        return rateRepository.findByTenantId(tenantId).stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("isBilled") == null
                        || Boolean.parseBoolean(filters.get("isBilled")) == (r.getBillId() != null))
                .map(this::rateToMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findRateById(String id) {
        AppliedBillingRate rate = rateRepository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("AppliedCustomerBillingRate", id));
        return rateToMap(rate);
    }

    private String writeJsonArray(List<Map<String, Object>> value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonArray(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, JSON_ARRAY);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
