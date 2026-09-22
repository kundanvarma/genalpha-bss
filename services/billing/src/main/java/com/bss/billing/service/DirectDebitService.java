package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.DirectDebitDtos.Claim;
import com.bss.billing.dto.DirectDebitDtos.ClaimRunReceipt;
import com.bss.billing.dto.DirectDebitDtos.ClaimView;
import com.bss.billing.dto.DirectDebitDtos.MandateFile;
import com.bss.billing.dto.DirectDebitDtos.MandateFileReceipt;
import com.bss.billing.dto.DirectDebitDtos.MandateRecord;
import com.bss.billing.dto.DirectDebitDtos.MandateView;
import com.bss.billing.dto.DirectDebitDtos.SettlementFileReceipt;
import com.bss.billing.dto.Money;
import com.bss.billing.dto.RelatedPartyRef;
import com.bss.billing.dto.RemittanceReceipt;
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
    public MandateFileReceipt ingestMandateFile(String tenantId, MandateFile file) {
        if (file == null || file.records() == null || file.records().isEmpty()) {
            throw new BadRequestException("a mandate file carries a 'records' list");
        }
        int registered = 0;
        int cancelled = 0;
        int skipped = 0;
        for (MandateRecord record : file.records()) {
            if (record == null) {
                skipped++;
                continue;
            }
            String partyRef = str(record.partyRef());
            String action = record.action() == null ? "add" : str(record.action());
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
            String accountRef = str(record.accountRef());
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
        return new MandateFileReceipt(registered, cancelled, skipped);
    }

    /**
     * The CYCLE RUN: every open bill of an active mandate holder becomes a
     * claim on the rail — KID from the bill's payment reference, the same
     * digits the settlement file will come home on. One claim per bill,
     * written only after the rail accepted it.
     */
    @Transactional
    public ClaimRunReceipt claimRun(String tenantId) {
        int sent = 0;
        int skipped = 0;
        List<Claim> sentClaims = new ArrayList<>();
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
                Claim claim = new Claim(kid, bill.getBillNo(), mandate.getPartyId(), mandate.getAccountRef(),
                        new Money(bill.getAmountDueUnit(), bill.getAmountDueValue()),
                        bill.getPeriodEnd().plusDays(14).toString());
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
        return new ClaimRunReceipt(sent, skipped, sentClaims);
    }

    /**
     * The settlement file home: OCR-style lines, ingested through the SAME
     * remittance door the bank webhook uses (exact-amount settle, TMF676
     * payment, unapplied-cash parking — nothing re-implemented), then the
     * matching claims flip to settled and the event says money arrived.
     */
    public SettlementFileReceipt applySettlementFile(String tenantId, String body) {
        RemittanceReceipt outcome = remittance.ingest(tenantId, body);
        List<ClaimView> settled = new ArrayList<>();
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
        SettlementFileReceipt view = new SettlementFileReceipt(outcome, "directDebit", settled, "SettlementFile");
        events.publish("SettlementReceivedEvent", "settlement", view);
        return view;
    }

    @Transactional(readOnly = true)
    public List<MandateView> mandateView(String tenantId, String partyId) {
        List<DirectDebitMandate> rows = partyId == null
                ? mandates.findTop100ByTenantIdOrderByRegisteredAtDesc(tenantId)
                : mandates.findByTenantIdAndPartyId(tenantId, partyId)
                        .map(List::of).orElse(List.of());
        return rows.stream().map(this::mandateView).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimView> claimView(String tenantId) {
        return claims.findTop100ByTenantIdOrderByRequestedAtDesc(tenantId)
                .stream().map(this::claimView).toList();
    }

    private MandateView mandateView(DirectDebitMandate m) {
        return new MandateView(m.getId(), m.getPartyId(), m.getAccountRef(), m.getStatus(),
                m.getRegisteredAt().toString(),
                m.getCancelledAt() == null ? null : m.getCancelledAt().toString(),
                List.of(RelatedPartyRef.customer(m.getPartyId())), "DirectDebitMandate");
    }

    private ClaimView claimView(DirectDebitClaim c) {
        return new ClaimView(c.getId(), c.getBillId(), c.getBillNo(), c.getKid(),
                new Money(c.getAmountUnit(), c.getAmountValue()), c.getStatus(),
                c.getRequestedAt().toString(),
                c.getSettledAt() == null ? null : c.getSettledAt().toString(), "DirectDebitClaim");
    }

    private static String str(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
