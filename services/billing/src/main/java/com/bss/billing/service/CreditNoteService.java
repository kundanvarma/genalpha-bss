package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CreditNote;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.DocumentSequence;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.exception.ConflictException;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CreditNoteRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.DocumentSequenceRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantScope;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CREDIT NOTES: the wrong invoice is never edited — the kreditnota
 * reverses it. Same information discipline as an invoice, an UNBROKEN
 * per-tenant number series (billing's first sequential counter), an
 * explicit reference to the bill it credits, and a REQUIRED reason.
 * Unpaid bill: the due comes down (a negative line that says why).
 * Settled bill: the money moves BACK through the PSP. Either way the
 * customer holds a numbered document, and the subledger hears about it.
 */
@Service
public class CreditNoteService {

    private static final Logger log = LoggerFactory.getLogger(CreditNoteService.class);
    private static final String SERIES = "creditNote";

    private final CreditNoteRepository creditNotes;
    private final DocumentSequenceRepository sequences;
    private final CustomerBillRepository bills;
    private final AppliedBillingRateRepository rates;
    private final DownstreamClients.PaymentClient payments;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final BillDistributionService distribution;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public CreditNoteService(CreditNoteRepository creditNotes, DocumentSequenceRepository sequences,
            CustomerBillRepository bills, AppliedBillingRateRepository rates,
            DownstreamClients.PaymentClient payments, DomainEventPublisher events,
            TenantScope tenantScope, PartyScope partyScope, BillDistributionService distribution) {
        this.distribution = distribution;
        this.creditNotes = creditNotes;
        this.sequences = sequences;
        this.bills = bills;
        this.rates = rates;
        this.payments = payments;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    @Transactional
    public Map<String, Object> issue(String billId, Map<String, Object> dto) {
        return issue(billId, dto, null);
    }

    @Transactional
    public Map<String, Object> issue(String billId, Map<String, Object> dto, String disputeId) {
        String tenant = tenantScope.currentTenantId();
        String reason = dto.get("reason") == null ? null : String.valueOf(dto.get("reason")).trim();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("reason is required — a reversing document has a cause");
        }
        CustomerBill bill = bills.findByIdAndTenantId(billId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("CustomerBill", billId));
        // per-LINE credits: name the rate lines being reversed; each may
        // carry its own partial amount (default = the full line)
        List<Map<String, Object>> creditedLines = resolveLines(bill, dto.get("lines"));
        BigDecimal amount = !creditedLines.isEmpty()
                ? creditedLines.stream().map(l -> (BigDecimal) l.get("amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : dto.get("amount") == null ? bill.getAmountDueValue()
                        : new BigDecimal(String.valueOf(dto.get("amount")));
        if (amount.signum() <= 0 || amount.compareTo(bill.getAmountDueValue()) > 0) {
            throw new BadRequestException("credit must be 0 < amount <= the bill's remaining "
                    + bill.getAmountDueValue());
        }

        CreditNote note = new CreditNote();
        note.setId(UUID.randomUUID().toString());
        note.setTenantId(tenant);
        note.setCreditNoteNo(nextNumber(tenant));
        note.setBillId(bill.getId());
        note.setBillNo(bill.getBillNo());
        note.setOwnerPartyId(bill.getOwnerPartyId());
        note.setAmountValue(amount);
        note.setAmountUnit(bill.getAmountDueUnit());
        note.setReason(reason);
        note.setDisputeId(disputeId);
        note.setIssuedAt(OffsetDateTime.now());

        if (CustomerBill.SETTLED.equals(bill.getState())) {
            // money already moved — it moves BACK, through the PSP
            String paymentId = settlingPaymentOf(bill);
            if (paymentId == null) {
                throw new ConflictException("settled bill has no payment reference to refund");
            }
            payments.refund(paymentId, amount, "credit note on " + bill.getBillNo());
            note.setSettlement(CreditNote.REFUNDED);
            note.setRefundRef(paymentId);
        } else {
            // unpaid: the numbered document AND the smaller due, atomically —
            // one negative rate line per credited line (or one for the lot)
            List<Map<String, Object>> negatives = creditedLines.isEmpty()
                    ? List.of(Map.of("name", reason, "amount", amount)) : creditedLines;
            for (Map<String, Object> lineCredit : negatives) {
                AppliedBillingRate credit = new AppliedBillingRate();
                credit.setId(UUID.randomUUID().toString());
                credit.setTenantId(tenant);
                credit.setName("Credit note " + note.getCreditNoteNo() + " — "
                        + lineCredit.get("name"));
                credit.setRateType("creditNote");
                credit.setAmountValue(((BigDecimal) lineCredit.get("amount")).negate());
                credit.setAmountUnit(bill.getAmountDueUnit());
                credit.setBillId(bill.getId());
                credit.setOwnerPartyId(bill.getOwnerPartyId());
                credit.setRateDate(OffsetDateTime.now());
                rates.save(credit);
            }
            bill.setAmountDueValue(bill.getAmountDueValue().subtract(amount));
            if (bill.getAmountDueValue().signum() == 0) {
                bill.setState(CustomerBill.SETTLED); // nothing left to collect
            }
            bill.setLastUpdate(OffsetDateTime.now());
            bills.save(bill);
            note.setSettlement(CreditNote.REDUCED);
        }
        if (!creditedLines.isEmpty()) {
            note.setLinesJson(writeJson(creditedLines));
        }
        creditNotes.save(note);
        // the kreditnota ships the same wire as the invoice it reverses
        distribution.distributeCreditNote(tenant, note, creditedLines);
        Map<String, Object> view = toMap(note);
        events.publish("CreditNoteIssuedEvent", "creditNote", view);
        log.info("credit note {} issued on bill {}: {} {} ({})", note.getCreditNoteNo(),
                bill.getBillNo(), amount, note.getAmountUnit(), note.getSettlement());
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String billId) {
        String tenant = tenantScope.currentTenantId();
        List<CreditNote> found = partyScope.scopedPartyId()
                .map(own -> creditNotes.findByTenantIdAndOwnerPartyIdOrderByIssuedAtDesc(tenant, own))
                .orElseGet(() -> billId != null
                        ? creditNotes.findByTenantIdAndBillIdOrderByIssuedAtDesc(tenant, billId)
                        : creditNotes.findByTenantIdOrderByIssuedAtDesc(tenant));
        return found.stream()
                .filter(n -> billId == null || billId.equals(n.getBillId()))
                .map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> byId(String id) {
        return toMap(requireOwn(id));
    }

    @Transactional(readOnly = true)
    public byte[] pdfOf(String id) {
        CreditNote note = requireOwn(id);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 48, 48, 56, 56);
            PdfWriter.getInstance(doc, out);
            doc.open();
            String tenant = note.getTenantId();
            Font brand = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(20, 118, 115));
            Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font dim = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            doc.add(new Paragraph(tenant.substring(0, 1).toUpperCase() + tenant.substring(1), brand));
            doc.add(new Paragraph("Credit note " + note.getCreditNoteNo(), h));
            doc.add(new Paragraph("Credits invoice: " + note.getBillNo(), dim));
            doc.add(new Paragraph("Customer: " + note.getOwnerPartyId(), dim));
            doc.add(new Paragraph("Issued: " + note.getIssuedAt(), dim));
            doc.add(new Paragraph("Settlement: " + (CreditNote.REFUNDED.equals(note.getSettlement())
                    ? "refunded to original payment method" : "deducted from amount due"), dim));
            doc.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(new float[]{4f, 1.2f});
            table.setWidthPercentage(100);
            table.addCell(headCell("Item"));
            table.addCell(headCell("Amount"));
            table.addCell(cell(note.getReason(), body, Element.ALIGN_LEFT));
            table.addCell(cell(String.format("-%.2f %s", note.getAmountValue(), note.getAmountUnit()),
                    body, Element.ALIGN_RIGHT));
            doc.add(table);
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph(String.format("Amount credited: %.2f %s",
                    note.getAmountValue(), note.getAmountUnit()), totalFont));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("credit note PDF failed: " + e.getMessage(), e);
        }
    }

    /** The EHF / Peppol BIS CreditNote XML — the legal wire format. */
    @Transactional(readOnly = true)
    public String xmlOf(String id) {
        CreditNote note = requireOwn(id);
        return distribution.creditNoteXml(note.getTenantId(), note, readLines(note.getLinesJson()));
    }

    /* ---------- internals ---------- */

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveLines(CustomerBill bill, Object linesDto) {
        if (!(linesDto instanceof List<?> requested) || requested.isEmpty()) {
            return List.of();
        }
        Map<String, AppliedBillingRate> byId = new LinkedHashMap<>();
        for (AppliedBillingRate r : rates.findByTenantIdAndBillId(bill.getTenantId(), bill.getId())) {
            byId.put(r.getId(), r);
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : requested) {
            if (!(o instanceof Map<?, ?> req) || req.get("id") == null) {
                throw new BadRequestException("each line needs the rate line's id");
            }
            AppliedBillingRate rate = byId.get(String.valueOf(req.get("id")));
            if (rate == null) {
                throw new BadRequestException("rate line " + req.get("id") + " is not on this bill");
            }
            if (rate.getAmountValue().signum() <= 0) {
                throw new BadRequestException("only positive charge lines can be credited");
            }
            BigDecimal lineAmount = req.get("amount") == null ? rate.getAmountValue()
                    : new BigDecimal(String.valueOf(req.get("amount")));
            if (lineAmount.signum() <= 0 || lineAmount.compareTo(rate.getAmountValue()) > 0) {
                throw new BadRequestException("line credit must be 0 < amount <= the line's "
                        + rate.getAmountValue());
            }
            out.add(Map.of("id", rate.getId(), "name", rate.getName(), "amount", lineAmount));
        }
        return out;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readLines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, List.class);
            // amounts come back as doubles from JSON — normalise for the XML
            return raw.stream().map(m -> (Map<String, Object>) (Map<?, ?>) Map.of(
                    "id", m.getOrDefault("id", ""), "name", m.getOrDefault("name", ""),
                    "amount", new BigDecimal(String.valueOf(m.get("amount"))))).toList();
        } catch (Exception e) {
            return List.of();
        }
    }


    private CreditNote requireOwn(String id) {
        CreditNote note = creditNotes.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CreditNote", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(note.getOwnerPartyId())) {
                throw NotFoundException.forResource("CreditNote", id);
            }
        });
        return note;
    }

    /** The gapless increment: row lock, read, bump — inside the issuing tx. */
    private String nextNumber(String tenant) {
        DocumentSequence seq = sequences.lockedRow(tenant, SERIES).orElseGet(() -> {
            DocumentSequence s = new DocumentSequence();
            s.setTenantId(tenant);
            s.setSeries(SERIES);
            s.setNextValue(1);
            return s;
        });
        long n = seq.getNextValue();
        seq.setNextValue(n + 1);
        sequences.save(seq);
        return String.format("CN-%06d", n);
    }

    @SuppressWarnings("unchecked")
    private String settlingPaymentOf(CustomerBill bill) {
        if (bill.getPaymentJson() == null || bill.getPaymentJson().isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> refs = objectMapper.readValue(bill.getPaymentJson(), List.class);
            return refs.isEmpty() || refs.get(0).get("id") == null ? null
                    : String.valueOf(refs.get(0).get("id"));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toMap(CreditNote n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.getId());
        map.put("href", "/tmf-api/customerBillManagement/v4/creditNote/" + n.getId());
        map.put("creditNoteNo", n.getCreditNoteNo());
        map.put("billId", n.getBillId());
        map.put("billNo", n.getBillNo());
        map.put("amount", Map.of("value", n.getAmountValue(), "unit",
                n.getAmountUnit() == null ? "EUR" : n.getAmountUnit()));
        map.put("reason", n.getReason());
        map.put("settlement", n.getSettlement());
        if (n.getRefundRef() != null) {
            map.put("refundRef", n.getRefundRef());
        }
        if (n.getDisputeId() != null) {
            map.put("disputeId", n.getDisputeId());
        }
        List<Map<String, Object>> creditedLines = readLines(n.getLinesJson());
        if (!creditedLines.isEmpty()) {
            map.put("creditedLines", creditedLines);
        }
        if (n.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", n.getOwnerPartyId(), "role", "customer",
                    "@referredType", "Individual")));
        }
        map.put("issuedAt", n.getIssuedAt());
        map.put("@type", "CreditNote");
        return map;
    }

    private static PdfPCell headCell(String text) {
        PdfPCell cell = new PdfPCell(new Paragraph(text,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        cell.setBackgroundColor(new Color(20, 118, 115));
        cell.setPadding(6);
        return cell;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setHorizontalAlignment(align);
        cell.setPadding(6);
        return cell;
    }
}
