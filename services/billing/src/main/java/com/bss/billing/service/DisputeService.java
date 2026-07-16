package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.BillDispute;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.exception.ConflictException;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.BillDisputeRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DISPUTES: "this charge is wrong." One open dispute per bill; while it
 * is open, collection pauses. Resolution is a decision with a name on
 * it: CREDIT — money off an unpaid bill (a negative line item that says
 * why), or a REAL refund back to the card on a settled one — or UPHOLD,
 * with the reason written down and told to the customer. Either way,
 * the customer is never left wondering.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final BillDisputeRepository disputes;
    private final CustomerBillRepository bills;
    private final AppliedBillingRateRepository rates;
    private final DownstreamClients.PaymentClient payments;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public DisputeService(BillDisputeRepository disputes, CustomerBillRepository bills,
            AppliedBillingRateRepository rates, DownstreamClients.PaymentClient payments,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope) {
        this.disputes = disputes;
        this.bills = bills;
        this.rates = rates;
        this.payments = payments;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    @Transactional
    public Map<String, Object> open(String billId, Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        CustomerBill bill = bills.findByIdAndTenantId(billId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("CustomerBill", billId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(bill.getOwnerPartyId())) {
                throw NotFoundException.forResource("CustomerBill", billId);
            }
        });
        String reason = dto.get("reason") == null ? null : String.valueOf(dto.get("reason")).trim();
        if (reason == null || reason.isEmpty()) {
            throw new BadRequestException("a dispute needs a reason — what looks wrong?");
        }
        if (disputes.existsByTenantIdAndBillIdAndStatus(tenant, billId, BillDispute.OPEN)) {
            throw new ConflictException("this bill already has an open dispute");
        }
        BillDispute dispute = new BillDispute();
        dispute.setId(UUID.randomUUID().toString());
        dispute.setTenantId(tenant);
        dispute.setBillId(billId);
        dispute.setPartyId(bill.getOwnerPartyId());
        dispute.setReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        dispute.setStatus(BillDispute.OPEN);
        dispute.setCreatedAt(OffsetDateTime.now());
        dispute.setLastUpdate(OffsetDateTime.now());
        disputes.save(dispute);
        Map<String, Object> view = toMap(dispute, bill);
        events.publish("DisputeOpenedEvent", "dispute", view);
        log.info("dispute opened on bill {} ({}): {}", bill.getBillNo(), billId, reason);
        return view;
    }

    /** Someone DECIDES: credit (an amount, with the money moving the right
     * way for the bill's state) or uphold (with the reason written down). */
    @Transactional
    public Map<String, Object> resolve(String disputeId, Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        BillDispute dispute = disputes.findByIdAndTenantId(disputeId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("Dispute", disputeId));
        if (!BillDispute.OPEN.equals(dispute.getStatus())) {
            throw new ConflictException("this dispute is already " + dispute.getStatus());
        }
        CustomerBill bill = bills.findByIdAndTenantId(dispute.getBillId(), tenant)
                .orElseThrow(() -> NotFoundException.forResource("CustomerBill", dispute.getBillId()));
        String outcome = String.valueOf(dto.getOrDefault("outcome", ""));
        String note = dto.get("note") == null ? null : String.valueOf(dto.get("note"));
        if ("uphold".equals(outcome)) {
            dispute.setStatus(BillDispute.UPHELD);
        } else if ("credit".equals(outcome)) {
            BigDecimal amount = dto.get("amount") == null ? null
                    : new BigDecimal(String.valueOf(dto.get("amount")));
            if (amount == null || amount.signum() <= 0
                    || amount.compareTo(bill.getAmountDueValue()) > 0) {
                throw new BadRequestException("credit must be 0 < amount <= the bill's "
                        + bill.getAmountDueValue());
            }
            if (CustomerBill.SETTLED.equals(bill.getState())) {
                // money already moved — it moves BACK, through the PSP
                String paymentId = settlingPaymentOf(bill);
                if (paymentId == null) {
                    throw new ConflictException("settled bill has no payment reference to refund");
                }
                payments.refund(paymentId, amount,
                        "dispute credit on " + bill.getBillNo());
            } else {
                // unpaid: a negative line that says why, and a smaller due
                AppliedBillingRate credit = new AppliedBillingRate();
                credit.setId(UUID.randomUUID().toString());
                credit.setTenantId(tenant);
                credit.setName("Dispute credit — " + (note != null ? note : dispute.getReason()));
                credit.setRateType("disputeCredit");
                credit.setAmountValue(amount.negate());
                credit.setAmountUnit(bill.getAmountDueUnit());
                credit.setBillId(bill.getId());
                credit.setOwnerPartyId(bill.getOwnerPartyId());
                credit.setRateDate(OffsetDateTime.now());
                rates.save(credit);
                bill.setAmountDueValue(bill.getAmountDueValue().subtract(amount));
                bill.setLastUpdate(OffsetDateTime.now());
                bills.save(bill);
            }
            dispute.setStatus(BillDispute.CREDITED);
            dispute.setCreditAmount(amount);
        } else {
            throw new BadRequestException("outcome must be 'credit' or 'uphold'");
        }
        dispute.setResolutionNote(note);
        dispute.setResolvedAt(OffsetDateTime.now());
        dispute.setLastUpdate(OffsetDateTime.now());
        disputes.save(dispute);
        Map<String, Object> view = toMap(dispute, bill);
        view.put("billState", bill.getState());
        events.publish("DisputeResolvedEvent", "dispute", view);
        log.info("dispute {} on bill {} resolved: {} {}", disputeId, bill.getBillNo(),
                dispute.getStatus(), dispute.getCreditAmount());
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        String tenant = tenantScope.currentTenantId();
        return disputes.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .map(d -> toMap(d, bills.findByIdAndTenantId(d.getBillId(), tenant).orElse(null)))
                .toList();
    }

    /** Collection never chases contested money. */
    @Transactional(readOnly = true)
    public boolean hasOpenDispute(String tenantId, String billId) {
        return disputes.existsByTenantIdAndBillIdAndStatus(tenantId, billId, BillDispute.OPEN);
    }

    @SuppressWarnings("unchecked")
    private String settlingPaymentOf(CustomerBill bill) {
        try {
            List<Map<String, Object>> refs = objectMapper.readValue(
                    bill.getPaymentJson(), List.class);
            return refs == null || refs.isEmpty() ? null : String.valueOf(refs.get(0).get("id"));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toMap(BillDispute d, CustomerBill bill) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", d.getId());
        map.put("billId", d.getBillId());
        if (bill != null) {
            map.put("billNo", bill.getBillNo());
        }
        map.put("reason", d.getReason());
        map.put("status", d.getStatus());
        if (d.getCreditAmount() != null) {
            map.put("creditAmount", d.getCreditAmount());
        }
        if (d.getResolutionNote() != null) {
            map.put("resolutionNote", d.getResolutionNote());
        }
        map.put("createdAt", d.getCreatedAt().toString());
        if (d.getPartyId() != null) {
            map.put("partyId", d.getPartyId());
            map.put("relatedParty", List.of(Map.of("id", d.getPartyId(), "role", "customer")));
        }
        map.put("@type", "BillDispute");
        return map;
    }
}
