package com.bss.billing.service;

import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The DISTRIBUTION seam: a finished bill leaves for the tenant's own
 * partner — a Peppol access point as e-invoice XML, or a print house as
 * a PDF. Formats are the EUROPEAN ones: EHF (Norway's CIUS of Peppol BIS
 * Billing 3.0), generic Peppol BIS, or UN/CEFACT CII — the two EN 16931
 * syntaxes; EDIFACT stays a named follow-up for legacy trading partners.
 * Fire-and-forget and fail-open: distribution never blocks billing, and
 * the in-app bill is always the record.
 */
@Service
public class BillDistributionService {

    private static final Logger log = LoggerFactory.getLogger(BillDistributionService.class);
    private static final String EHF_CUSTOMIZATION =
            "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";
    private static final String PEPPOL_PROFILE = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    private final TenantRegistry tenants;
    private final BillDocumentService documents;
    private final RestClient restClient;

    public BillDistributionService(TenantRegistry tenants, BillDocumentService documents,
            RestClient.Builder builder) {
        this.tenants = tenants;
        this.documents = documents;
        this.restClient = builder.build();
    }

    public void distribute(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || !"partner".equalsIgnoreCase(tenant.getBillDistributionProvider())
                || tenant.getBillDistributionUrl() == null
                || tenant.getBillDistributionUrl().isBlank()) {
            return;
        }
        String format = tenant.getBillDistributionFormat();
        String channel = tenant.getBillDistributionChannel();
        // render on the caller's thread (entities are session-bound); ship async
        String payload;
        String contentType;
        if ("print".equalsIgnoreCase(channel)) {
            payload = Base64.getEncoder().encodeToString(documents.render(tenantId, bill, lines));
            contentType = "application/pdf+base64";
        } else if ("cii".equalsIgnoreCase(format)) {
            payload = ciiOf(bill, lines);
            contentType = "application/xml";
        } else {
            payload = ublOf(tenantId, bill, lines, "ehf".equalsIgnoreCase(format));
            contentType = "application/xml";
        }
        Map<String, Object> envelope = Map.of(
                "billNo", bill.getBillNo(),
                "format", "print".equalsIgnoreCase(channel) ? "pdf" : format,
                "channel", channel,
                "recipient", bill.getOwnerPartyId(),
                "contentType", contentType,
                "payload", payload);
        CompletableFuture.runAsync(() -> {
            try {
                restClient.post().uri(tenant.getBillDistributionUrl() + "/invoices")
                        .header("Authorization", "Bearer " + tenant.getBillDistributionToken())
                        .header("Content-Type", "application/json")
                        .body(envelope)
                        .retrieve().toBodilessEntity();
                log.info("bill {} distributed: {} via {} ({})", bill.getBillNo(),
                        format, channel, tenant.getBillDistributionUrl());
            } catch (Exception e) {
                // fail-open: the in-app bill is the record; ops reconciles
                log.warn("bill {} distribution failed ({}) — reconcile later",
                        bill.getBillNo(), e.getMessage());
            }
        });
    }

    /** UBL 2.1 Invoice — the EN 16931 core in Peppol BIS 3.0 clothing;
     * EHF is the same document with Norway's customization declared and
     * the payment reference (the KID slot) carried. */
    private String ublOf(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines,
            boolean ehf) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"\n")
           .append("         xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"\n")
           .append("         xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");
        xml.append("  <cbc:CustomizationID>").append(EHF_CUSTOMIZATION).append("</cbc:CustomizationID>\n");
        xml.append("  <cbc:ProfileID>").append(PEPPOL_PROFILE).append("</cbc:ProfileID>\n");
        xml.append("  <cbc:ID>").append(bill.getBillNo()).append("</cbc:ID>\n");
        xml.append("  <cbc:IssueDate>").append(bill.getPeriodEnd()).append("</cbc:IssueDate>\n");
        xml.append("  <cbc:DueDate>").append(bill.getPeriodEnd().plusDays(14)).append("</cbc:DueDate>\n");
        xml.append("  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>\n");
        xml.append("  <cbc:DocumentCurrencyCode>").append(bill.getAmountDueUnit()).append("</cbc:DocumentCurrencyCode>\n");
        if (ehf) {
            // Norway's NO-R rules want a payment reference — the KID slot
            xml.append("  <cac:PaymentMeans><cbc:PaymentID>")
               .append(bill.getBillNo().replaceAll("\\D", ""))
               .append("</cbc:PaymentID></cac:PaymentMeans>\n");
        }
        xml.append("  <cac:AccountingSupplierParty><cac:Party><cac:PartyName><cbc:Name>")
           .append(tenantId).append("</cbc:Name></cac:PartyName></cac:Party></cac:AccountingSupplierParty>\n");
        xml.append("  <cac:AccountingCustomerParty><cac:Party><cac:PartyIdentification><cbc:ID>")
           .append(bill.getOwnerPartyId()).append("</cbc:ID></cac:PartyIdentification></cac:Party></cac:AccountingCustomerParty>\n");
        xml.append("  <cac:LegalMonetaryTotal><cbc:PayableAmount currencyID=\"")
           .append(bill.getAmountDueUnit()).append("\">")
           .append(bill.getAmountDueValue()).append("</cbc:PayableAmount></cac:LegalMonetaryTotal>\n");
        int lineNo = 1;
        for (AppliedBillingRate line : lines) {
            xml.append("  <cac:InvoiceLine><cbc:ID>").append(lineNo++).append("</cbc:ID>")
               .append("<cbc:InvoicedQuantity unitCode=\"C62\">1</cbc:InvoicedQuantity>")
               .append("<cbc:LineExtensionAmount currencyID=\"").append(line.getAmountUnit()).append("\">")
               .append(line.getAmountValue()).append("</cbc:LineExtensionAmount>")
               .append("<cac:Item><cbc:Name>").append(escape(line.getName())).append("</cbc:Name></cac:Item>")
               .append("</cac:InvoiceLine>\n");
        }
        xml.append("</Invoice>\n");
        return xml.toString();
    }

    /** UN/CEFACT CII — EN 16931's other syntax, for the DACH/France half. */
    private String ciiOf(CustomerBill bill, List<AppliedBillingRate> lines) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\"\n")
           .append("    xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\">\n");
        xml.append("  <rsm:ExchangedDocument><ram:ID>").append(bill.getBillNo())
           .append("</ram:ID><ram:TypeCode>380</ram:TypeCode>")
           .append("<ram:IssueDateTime>").append(bill.getPeriodEnd().format(DateTimeFormatter.BASIC_ISO_DATE))
           .append("</ram:IssueDateTime></rsm:ExchangedDocument>\n");
        xml.append("  <rsm:SupplyChainTradeTransaction>\n");
        for (AppliedBillingRate line : lines) {
            xml.append("    <ram:IncludedSupplyChainTradeLineItem><ram:SpecifiedTradeProduct><ram:Name>")
               .append(escape(line.getName())).append("</ram:Name></ram:SpecifiedTradeProduct>")
               .append("<ram:LineTotalAmount>").append(line.getAmountValue())
               .append("</ram:LineTotalAmount></ram:IncludedSupplyChainTradeLineItem>\n");
        }
        xml.append("    <ram:ApplicableHeaderTradeSettlement><ram:DuePayableAmount currencyID=\"")
           .append(bill.getAmountDueUnit()).append("\">").append(bill.getAmountDueValue())
           .append("</ram:DuePayableAmount></ram:ApplicableHeaderTradeSettlement>\n");
        xml.append("  </rsm:SupplyChainTradeTransaction>\n</rsm:CrossIndustryInvoice>\n");
        return xml.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
