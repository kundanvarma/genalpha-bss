package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.DirectDebitClaim;
import com.bss.billing.entity.DirectDebitMandate;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.DirectDebitClaimRepository;
import com.bss.billing.repository.DirectDebitMandateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The direct-debit loop, file-batch honest end to end: mandates arrive in
 * a BATCH FILE from the bank (add/delete records — never a console edit),
 * the cycle run turns open bills of mandate holders into claims on the
 * rail (one claim per bill, ever), and the settlement file comes home as
 * OCR lines that settle bills through the SAME remittance door the bank
 * webhook uses — every guarantee (exact amount, open bill, idempotent
 * correlator, unapplied-cash parking) included.
 */
@Service
public class DirectDebitService {

    private static final Logger log = LoggerFactory.getLogger(DirectDebitService.class);

    private final DirectDebitMandateRepository mandates;
    private final DirectDebitClaimRepository claims;
    private final CustomerBillRepository bills;
    private final DownstreamClients.DirectDebitClient rail;
    private final RemittanceService remittance;
    private final DomainEventPublisher events;

    public DirectDebitService(DirectDebitMandateRepository mandates,
            DirectDebitClaimRepository claims, CustomerBillRepository bills,
            DownstreamClients.DirectDebitClient rail, RemittanceService remittance,
            DomainEventPublisher events) {
        this.mandates = mandates;
        this.claims = claims;
        this.bills = bills;
        this.rail = rail;
        this.remittance = remittance;
        this.events = events;
    }

    /** One mandate batch in: add registers (upsert — a re-posted file books
     * nothing twice), delete cancels. Each record answers for itself. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestMandateFile(String tenantId, Map<String, Object> file) {
        if (!(file.get("records") instanceof List<?> records) || records.isEmpty()) {
            throw new BadRequestException("a mandate file carries a 'records' list");
        }
        int registered = 0;
        int cancelled = 0;
        int skipped = 0;
        for (Object o : records) {
            if (!(o instanceof Map<?, ?> raw)) {
                skipped++;
                continue;
            }
            Map<String, Object> record = (Map<String, Object>) raw;
            String partyRef = str(record.get("partyRef"));
            String action = record.get("action") == null ? "add" : str(record.get("action"));
            if (partyRef == null) {
                skipped++;
                continue;
            }
            DirectDebitMandate mandate = mandates
                    .findByTenantIdAndPartyId(tenantId, partyRef).orElse(null);
            if ("delete".equalsIgnoreCase(action)) {
                if (mandate == null || DirectDebitMandate.CANCELLED.equals(mandate.getStatus())) {
                    skipped++;
                    continue;
                }
                mandate.setStatus(DirectDebitMandate.CANCELLED);
                mandate.setCancelledAt(OffsetDateTime.now());
                mandate.setLastUpdate(OffsetDateTime.now());
                mandates.save(mandate);
                cancelled++;
                events.publish("MandateCancelledEvent", "directDebitMandate", mandateView(mandate));
                continue;
            }
            String accountRef = str(record.get("accountRef"));
            if (accountRef == null) {
                skipped++;
                continue;
            }
            boolean fresh = mandate == null;
            if (fresh) {
                mandate = new DirectDebitMandate();
                mandate.setId(UUID.randomUUID().toString());
                mandate.setTenantId(tenantId);
                mandate.setPartyId(partyRef);
                mandate.setRegisteredAt(OffsetDateTime.now());
            } else if (DirectDebitMandate.ACTIVE.equals(mandate.getStatus())
                    && accountRef.equals(mandate.getAccountRef())) {
                skipped++; // the re-posted file: already active, nothing to say
                continue;
            }
            mandate.setAccountRef(accountRef);
            mandate.setStatus(DirectDebitMandate.ACTIVE);
            mandate.setCancelledAt(null);
            mandate.setLastUpdate(OffsetDateTime.now());
            mandates.save(mandate);
            registered++;
            events.publish("MandateRegisteredEvent", "directDebitMandate", mandateView(mandate));
        }
        return Map.of("registered", registered, "cancelled", cancelled, "skipped", skipped);
    }

    /**
     * The CYCLE RUN: every open bill of an active mandate holder becomes a
     * claim on the rail — KID from the bill's payment reference, the same
     * digits the settlement file will come home on. One claim per bill,
     * written only after the rail accepted it.
     */
    @Transactional
    public Map<String, Object> claimRun(String tenantId) {
        int sent = 0;
        int skipped = 0;
        List<Map<String, Object>> sentClaims = new ArrayList<>();
        for (DirectDebitMandate mandate : mandates
                .findByTenantIdAndStatus(tenantId, DirectDebitMandate.ACTIVE)) {
            for (CustomerBill bill : bills.findByTenantIdAndOwnerPartyIdAndState(
                    tenantId, mandate.getPartyId(), CustomerBill.NEW)) {
                if (claims.findByTenantIdAndBillId(tenantId, bill.getId()).isPresent()) {
                    skipped++;
                    continue;
                }
                String kid = bill.getPaymentReference() != null ? bill.getPaymentReference()
                        : bill.getBillNo().replaceAll("\\D", "");
                Map<String, Object> claim = Map.of(
                        "kid", kid,
                        "billNo", bill.getBillNo(),
                        "partyRef", mandate.getPartyId(),
                        "accountRef", mandate.getAccountRef(),
                        "amount", Map.of("value", bill.getAmountDueValue(),
                                "unit", bill.getAmountDueUnit()),
                        "dueDate", bill.getPeriodEnd().plusDays(14).toString());
                rail.sendClaim(claim);
                DirectDebitClaim row = new DirectDebitClaim();
                row.setId(UUID.randomUUID().toString());
                row.setTenantId(tenantId);
                row.setMandateId(mandate.getId());
                row.setBillId(bill.getId());
                row.setBillNo(bill.getBillNo());
                row.setKid(kid);
                row.setAmountValue(bill.getAmountDueValue());
                row.setAmountUnit(bill.getAmountDueUnit());
                row.setStatus(DirectDebitClaim.REQUESTED);
                row.setRequestedAt(OffsetDateTime.now());
                claims.save(row);
                sent++;
                sentClaims.add(claim);
                log.info("direct-debit claim out: {} for {} (KID {})",
                        bill.getBillNo(), mandate.getPartyId(), kid);
            }
        }
        return Map.of("claims", sent, "skipped", skipped, "sentClaims", sentClaims);
    }

    /**
     * The settlement file home: OCR-style lines, ingested through the SAME
     * remittance door the bank webhook uses (exact-amount settle, TMF676
     * payment, unapplied-cash parking — nothing re-implemented), then the
     * matching claims flip to settled and the event says money arrived.
     */
    public Map<String, Object> applySettlementFile(String tenantId, String body) {
        Map<String, Object> outcome = remittance.ingest(tenantId, body);
        List<Map<String, Object>> settled = new ArrayList<>();
        for (DirectDebitClaim claim : claims.findTop100ByTenantIdOrderByRequestedAtDesc(tenantId)) {
            if (!DirectDebitClaim.REQUESTED.equals(claim.getStatus())) {
                continue;
            }
            boolean billSettled = bills.findByIdAndTenantId(claim.getBillId(), tenantId)
                    .map(b -> CustomerBill.SETTLED.equals(b.getState())).orElse(false);
            if (billSettled) {
                claim.setStatus(DirectDebitClaim.SETTLED);
                claim.setSettledAt(OffsetDateTime.now());
                claims.save(claim);
                settled.add(claimView(claim));
            }
        }
        Map<String, Object> view = new LinkedHashMap<>(outcome);
        view.put("source", "directDebit");
        view.put("settledClaims", settled);
        view.put("@type", "SettlementFile");
        events.publish("SettlementReceivedEvent", "settlement", view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mandateView(String tenantId, String partyId) {
        List<DirectDebitMandate> rows = partyId == null
                ? mandates.findTop100ByTenantIdOrderByRegisteredAtDesc(tenantId)
                : mandates.findByTenantIdAndPartyId(tenantId, partyId)
                        .map(List::of).orElse(List.of());
        return rows.stream().map(this::mandateView).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> claimView(String tenantId) {
        return claims.findTop100ByTenantIdOrderByRequestedAtDesc(tenantId)
                .stream().map(this::claimView).toList();
    }

    private Map<String, Object> mandateView(DirectDebitMandate m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("partyId", m.getPartyId());
        map.put("accountRef", m.getAccountRef());
        map.put("status", m.getStatus());
        map.put("registeredAt", m.getRegisteredAt().toString());
        map.put("cancelledAt", m.getCancelledAt() == null ? null : m.getCancelledAt().toString());
        map.put("relatedParty", List.of(Map.of("id", m.getPartyId(), "role", "customer")));
        map.put("@type", "DirectDebitMandate");
        return map;
    }

    private Map<String, Object> claimView(DirectDebitClaim c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("billId", c.getBillId());
        map.put("billNo", c.getBillNo());
        map.put("kid", c.getKid());
        map.put("amount", Map.of("unit", c.getAmountUnit(), "value", c.getAmountValue()));
        map.put("status", c.getStatus());
        map.put("requestedAt", c.getRequestedAt().toString());
        map.put("settledAt", c.getSettledAt() == null ? null : c.getSettledAt().toString());
        map.put("@type", "DirectDebitClaim");
        return map;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
