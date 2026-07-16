package com.bss.billing.service;

import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.exception.NotFoundException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantScope;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The bill as a DOCUMENT: a clean, self-contained PDF a person can save,
 * print or forward — rendered from the same applied rates the API serves,
 * so the paper never disagrees with the data. Owner-scoped exactly like
 * the bill itself.
 */
@Service
public class BillDocumentService {

    private final CustomerBillRepository bills;
    private final AppliedBillingRateRepository rates;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final com.bss.billing.events.DomainEventPublisher events;

    public BillDocumentService(CustomerBillRepository bills, AppliedBillingRateRepository rates,
            TenantScope tenantScope, PartyScope partyScope,
            com.bss.billing.events.DomainEventPublisher events) {
        this.bills = bills;
        this.rates = rates;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public byte[] pdfOf(String billId) {
        String tenant = tenantScope.currentTenantId();
        CustomerBill bill = bills.findByIdAndTenantId(billId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("CustomerBill", billId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(bill.getOwnerPartyId())) {
                throw NotFoundException.forResource("CustomerBill", billId);
            }
        });
        List<AppliedBillingRate> lines = rates.findByTenantIdAndBillId(tenant, billId);
        return render(tenant, bill, lines);
    }

    /**
     * "Can you send me a copy of my invoice?" — the single most common
     * billing request a care agent hears. This renders the SAME PDF the
     * customer sees and hands it to the notification loop, which emails
     * it to the address ON FILE — never one dictated over the phone
     * (that door stays shut to social engineering). Owner-scoped like
     * the PDF itself, so a customer can also self-serve it.
     */
    @Transactional
    public java.util.Map<String, Object> resend(String billId) {
        String tenant = tenantScope.currentTenantId();
        CustomerBill bill = bills.findByIdAndTenantId(billId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("CustomerBill", billId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(bill.getOwnerPartyId())) {
                throw NotFoundException.forResource("CustomerBill", billId);
            }
        });
        List<AppliedBillingRate> lines = rates.findByTenantIdAndBillId(tenant, billId);
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", billId);
        view.put("billNo", bill.getBillNo());
        view.put("amountDue", String.format("%.2f %s", bill.getAmountDueValue(),
                bill.getAmountDueUnit()));
        view.put("pdfBase64", java.util.Base64.getEncoder()
                .encodeToString(render(tenant, bill, lines)));
        view.put("relatedParty", List.of(java.util.Map.of(
                "id", bill.getOwnerPartyId(), "role", "customer")));
        view.put("@type", "BillResend");
        events.publish("CustomerBillResendEvent", "billResend", view);
        return java.util.Map.of("sent", bill.getBillNo());
    }

    /** Also used by the distribution seam for the PRINT channel. */
    byte[] render(String tenant, CustomerBill bill, List<AppliedBillingRate> lines) {
        return render(tenant, bill, lines, null);
    }

    /**
     * With an optional machine-readable soul: Factur-X/ZUGFeRD embeds the
     * EN 16931 CII XML inside the human-readable PDF as "factur-x.xml" —
     * one file, two audiences. (Full Factur-X conformance also demands
     * PDF/A-3 + XMP metadata; this carries the load-bearing part, the
     * embedded structured invoice.)
     */
    byte[] render(String tenant, CustomerBill bill, List<AppliedBillingRate> lines,
            byte[] facturXml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 48, 48, 56, 56);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            if (facturXml != null) {
                // uncompressed embed: the structured invoice stays readable
                // to any byte-level inspector, not only full PDF parsers
                writer.addFileAttachment("Factur-X invoice data (EN 16931 CII)",
                        com.lowagie.text.pdf.PdfFileSpecification.fileEmbedded(
                                writer, null, "factur-x.xml", facturXml, false));
            }

            Font brand = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(20, 118, 115));
            Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font dim = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);

            doc.add(new Paragraph(tenant.substring(0, 1).toUpperCase() + tenant.substring(1), brand));
            doc.add(new Paragraph("Invoice " + bill.getBillNo(), h));
            doc.add(new Paragraph("Billing period: " + bill.getPeriodStart()
                    + " to " + bill.getPeriodEnd(), dim));
            doc.add(new Paragraph("Customer: " + bill.getOwnerPartyId(), dim));
            doc.add(new Paragraph("State: " + bill.getState(), dim));
            doc.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(new float[]{4f, 1.2f});
            table.setWidthPercentage(100);
            table.addCell(headCell("Item"));
            table.addCell(headCell("Amount"));
            for (AppliedBillingRate line : lines) {
                table.addCell(cell(line.getName(), body, Element.ALIGN_LEFT));
                table.addCell(cell(String.format("%.2f %s", line.getAmountValue(),
                        line.getAmountUnit()), body, Element.ALIGN_RIGHT));
            }
            PdfPCell label = cell("Amount due", totalFont, Element.ALIGN_LEFT);
            label.setBorderWidthTop(1f);
            table.addCell(label);
            PdfPCell amount = cell(String.format("%.2f %s", bill.getAmountDueValue(),
                    bill.getAmountDueUnit()), totalFont, Element.ALIGN_RIGHT);
            amount.setBorderWidthTop(1f);
            table.addCell(amount);
            doc.add(table);

            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Every line names the days it covers — the bill explains"
                    + " itself. Questions or a charge that looks wrong? Dispute it from"
                    + " My bills; collection pauses while we look.", dim));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("bill PDF rendering failed", e);
        }
    }

    private PdfPCell headCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.GRAY)));
        cell.setBorder(PdfPCell.BOTTOM);
        cell.setPaddingBottom(6);
        return cell;
    }

    private PdfPCell cell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setHorizontalAlignment(align);
        cell.setPadding(4);
        return cell;
    }
}
