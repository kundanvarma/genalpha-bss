package com.bss.billing.service;

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
    private final ObjectMapper objectMapper;

    public CustomerBillService(CustomerBillRepository repository, AppliedBillingRateRepository rateRepository,
            DownstreamClients.PaymentClient paymentClient, DomainEventPublisher events, PartyScope partyScope,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.rateRepository = rateRepository;
        this.paymentClient = paymentClient;
        this.events = events;
        this.partyScope = partyScope;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<CustomerBillDto> findAll(int offset, int limit, Map<String, String> filters) {
        CustomerBill probe = probeFor(filters);
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
                case "state" -> probe.setState(f.getValue());
                case "billNo" -> probe.setBillNo(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public CustomerBillDto findById(String id) {
        CustomerBill entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> ratesOf(String billId) {
        CustomerBill bill = repository.findById(billId)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, billId));
        requireOwn(bill);
        return rateRepository.findByBillId(billId).stream().map(this::rateToMap).toList();
    }

    /**
     * The one legal change: settling a new bill with an authorized payment
     * covering the amount due — the payment is captured in the same breath.
     */
    @Transactional
    public CustomerBillDto settle(String id, CustomerBillDto patch) {
        CustomerBill entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!CustomerBill.SETTLED.equals(patch.getState())) {
            throw new BadRequestException("the only supported change is state: 'settled' with a payment");
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
        dto.setPayment(readJsonArray(entity.getPaymentJson()));
        dto.setBillDate(entity.getBillDate());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("CustomerBill");
        return dto;
    }

    private Map<String, Object> rateToMap(AppliedBillingRate rate) {
        return Map.of(
                "id", rate.getId(),
                "name", rate.getName(),
                "@type", "AppliedCustomerBillingRate",
                "type", rate.getRateType(),
                "taxExcludedAmount", Map.of("unit", rate.getAmountUnit(), "value", rate.getAmountValue()),
                "bill", Map.of("id", rate.getBillId()),
                "date", String.valueOf(rate.getRateDate()));
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
