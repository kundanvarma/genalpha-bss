package com.bss.billing.service;

import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.BillDistribution;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The DISTRIBUTION seam: a finished bill leaves for the tenant's own
 * partner — a Peppol access point as e-invoice XML, or a print house as
 * a PDF. Formats are the EUROPEAN ones: EHF (Norway's CIUS of Peppol BIS
 * Billing 3.0), generic Peppol BIS, or UN/CEFACT CII — the two EN 16931
 * syntaxes; EDIFACT stays a named follow-up for legacy trading partners.
 * OUTBOX-BACKED: cutting a bill writes a ledger row in the same
 * transaction; the relay drains it with exponential backoff, so a dead
 * partner delays delivery but never loses it — and never blocks a
 * billing run. The in-app bill is always the record.
 */
@Service
public class BillDistributionService {

    private static final Logger log = LoggerFactory.getLogger(BillDistributionService.class);
    private static final String EHF_CUSTOMIZATION =
            "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";
    private static final String PEPPOL_PROFILE = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    private final TenantRegistry tenants;
    private final BillDocumentService documents;
    private final com.bss.billing.repository.BillFormatProfileRepository profiles;
    private final com.bss.billing.repository.BillDistributionRepository ledger;
    private final RestClient restClient;
    private final com.bss.billing.tick.TickGuard tickGuard;
    private final BillChannelService channels;
    private final com.bss.billing.client.DownstreamClients.OrgClient orgs;
    private final com.bss.billing.events.DomainEventPublisher events;
    private final String einvoiceRailUrl;
    private final String mailboxRailUrl;
    private final long retrySeconds;
    private final int maxAttempts;

    /** What the renderer needs of a profile — a row, or the built-in
     * fallback when a tenant names a format it has no row for. */
    record Profile(String syntax, String customizationId, String profileId,
            boolean paymentReference) {
    }

    public BillDistributionService(TenantRegistry tenants, BillDocumentService documents,
            com.bss.billing.repository.BillFormatProfileRepository profiles,
            com.bss.billing.repository.BillDistributionRepository ledger,
            RestClient.Builder builder, com.bss.billing.tick.TickGuard tickGuard,
            BillChannelService channels,
            com.bss.billing.client.DownstreamClients.OrgClient orgs,
            com.bss.billing.events.DomainEventPublisher events,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.downstream.einvoice-rail-base-url:http://localhost:8147}") String einvoiceRailUrl,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.downstream.mailbox-rail-base-url:http://localhost:8148}") String mailboxRailUrl,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.billing.distribution-retry-seconds:60}") long retrySeconds,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.billing.distribution-max-attempts:8}") int maxAttempts) {
        this.tenants = tenants;
        this.documents = documents;
        this.profiles = profiles;
        this.ledger = ledger;
        this.restClient = builder.build();
        this.tickGuard = tickGuard;
        this.channels = channels;
        this.orgs = orgs;
        this.events = events;
        this.einvoiceRailUrl = einvoiceRailUrl;
        this.mailboxRailUrl = mailboxRailUrl;
        this.retrySeconds = retrySeconds;
        this.maxAttempts = maxAttempts;
    }

    /** The profile is a CONFIG ROW: the tenant's format code picks it,
     * an admin edits it live, and a new country is an INSERT. The
     * built-in EHF/CII shapes remain the fail-open fallback so a
     * missing row never blocks a billing run. */
    private Profile profileOf(String tenantId, String format) {
        return profiles.findByTenantIdAndCode(tenantId, format)
                .map(p -> new Profile(p.getSyntax(), p.getCustomizationId(),
                        p.getProfileId(), p.isPaymentReference()))
                .orElseGet(() -> "cii".equalsIgnoreCase(format)
                        ? new Profile("cii", null, null, false)
                        : new Profile("ubl", EHF_CUSTOMIZATION, PEPPOL_PROFILE,
                                "ehf".equalsIgnoreCase(format)));
    }

    public void distribute(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines) {
        distribute(tenantId, bill, lines, null);
    }

    /**
     * With the CUSTOMER's delivery preference deciding the channel:
     * 'paper' prints, 'einvoice' ships XML, 'digital' skips the partner
     * entirely (in-app and email are the delivery), null takes the
     * tenant's default. The partner, token and XML syntax stay tenant
     * config — the customer picks the channel, never the plumbing.
     */
    public void distribute(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines,
            String preference) {
        // the CONSENT CHAIN first: a per-party channel row (e-invoice rail,
        // resolved against a LIVE alias, or the digital mailbox) beats the
        // tenant/preference plumbing below — an alias miss at send time
        // falls through here, and the print path is the floor
        java.util.Optional<BillChannelService.ResolvedChannel> consent =
                channels.resolve(tenantId, bill.getOwnerPartyId());
        if (consent.isPresent()) {
            String channel = consent.get().channel();
            String kid = bill.getPaymentReference() != null ? bill.getPaymentReference()
                    : bill.getBillNo().replaceAll("\\D", "");
            Map<String, Object> payload = "efaktura".equals(channel)
                    ? rfpOf(bill, kid, consent.get().aliasRef())
                    : letterOf(orgs.partyOf(bill.getOwnerPartyId()).orElse(null), bill, lines, kid);
            enqueue(tenantId, bill, channel, payload);
            bill.setDistributionChannel(channel);
            return;
        }
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || !"partner".equalsIgnoreCase(tenant.getBillDistributionProvider())
                || tenant.getBillDistributionUrl() == null
                || tenant.getBillDistributionUrl().isBlank()) {
            return;
        }
        if ("digital".equalsIgnoreCase(preference)) {
            log.info("bill {} not distributed: the customer chose digital-only delivery",
                    bill.getBillNo());
            bill.setDistributionChannel("digital");
            return;
        }
        String format = tenant.getBillDistributionFormat();
        String channel = "paper".equalsIgnoreCase(preference) ? "print"
                : "einvoice".equalsIgnoreCase(preference) ? "einvoice"
                : tenant.getBillDistributionChannel();
        // render on the caller's thread (entities and the profile row are
        // session-bound) and write the LEDGER ROW in the same transaction
        // that cuts the bill — if the bill exists, the delivery intent
        // exists; the relay does the actual sending, with retries
        String payload;
        String contentType;
        String wireFormat;
        if ("print".equalsIgnoreCase(channel)) {
            payload = Base64.getEncoder().encodeToString(documents.render(tenantId, bill, lines));
            contentType = "application/pdf+base64";
            wireFormat = "pdf";
        } else {
            Profile profile = profileOf(tenantId, format);
            switch (profile.syntax() == null ? "ubl" : profile.syntax().toLowerCase()) {
                case "cii" -> {
                    payload = ciiOf(bill, lines);
                    contentType = "application/xml";
                    wireFormat = format;
                }
                case "edifact" -> {
                    // the pre-XML lingua franca: segments, not tags
                    payload = edifactOf(tenantId, bill, lines);
                    contentType = "application/EDIFACT";
                    wireFormat = "edifact";
                }
                case "facturx" -> {
                    // the hybrid: the human PDF with the CII inside it
                    payload = Base64.getEncoder().encodeToString(documents.render(tenantId,
                            bill, lines, ciiOf(bill, lines).getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
                    contentType = "application/pdf+base64";
                    wireFormat = "facturx";
                }
                default -> {
                    payload = ublOf(tenantId, bill, lines, profile);
                    contentType = "application/xml";
                    wireFormat = format;
                }
            }
        }
        BillDistribution row = new BillDistribution();
        row.setId(java.util.UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setBillId(bill.getId());
        row.setBillNo(bill.getBillNo());
        row.setFormat(wireFormat);
        row.setChannel(channel);
        row.setRecipient(bill.getOwnerPartyId());
        row.setContentType(contentType);
        row.setPayload(payload);
        row.setStatus(BillDistribution.PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(OffsetDateTime.now());
        row.setCreatedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        ledger.save(row);
        bill.setDistributionChannel(channel);
    }

    /** A rail send rides the SAME outbox-backed ledger as the partner path
     * — written with the bill, drained by the relay, retried honestly. */
    private void enqueue(String tenantId, CustomerBill bill, String channel,
            Map<String, Object> payload) {
        BillDistribution row = new BillDistribution();
        row.setId(java.util.UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setBillId(bill.getId());
        row.setBillNo(bill.getBillNo());
        row.setFormat("efaktura".equals(channel) ? "rfp" : "letter");
        row.setChannel(channel);
        row.setRecipient(bill.getOwnerPartyId());
        row.setContentType("application/json");
        row.setPayload(toJson(payload));
        row.setStatus(BillDistribution.PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(OffsetDateTime.now());
        row.setCreatedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        ledger.save(row);
    }

    /** The e-invoice rail's request-for-payment: the KID IS the bill's
     * payment reference — the settlement file comes home on the same digits. */
    static Map<String, Object> rfpOf(CustomerBill bill, String kid, String aliasRef) {
        Map<String, Object> rfp = new java.util.LinkedHashMap<>();
        rfp.put("kid", kid);
        rfp.put("billNo", bill.getBillNo());
        rfp.put("aliasRef", aliasRef);
        rfp.put("partyRef", bill.getOwnerPartyId());
        rfp.put("amount", Map.of("value", bill.getAmountDueValue(), "unit", bill.getAmountDueUnit()));
        rfp.put("issueDate", bill.getPeriodEnd().toString());
        rfp.put("dueDate", bill.getPeriodEnd().plusDays(14).toString());
        return rfp;
    }

    /**
     * The mailbox letter, built ONLY from the party API's view — which is
     * already masked for protected addresses, so a protected party's letter
     * carries no street by construction (postCode/city survive for routing).
     * The addressing is the mailbox provider's job; ours is honest content.
     */
    public static Map<String, Object> letterOf(Map<String, Object> party, CustomerBill bill,
            List<AppliedBillingRate> lines, String kid) {
        Map<String, Object> letter = new java.util.LinkedHashMap<>();
        letter.put("partyRef", bill.getOwnerPartyId());
        letter.put("subject", "Your invoice " + bill.getBillNo());
        StringBuilder content = new StringBuilder();
        String name = party == null ? null : ((String.valueOf(party.getOrDefault("givenName", ""))
                + " " + String.valueOf(party.getOrDefault("familyName", ""))).trim());
        if (name != null && !name.isBlank()) {
            content.append(name).append('\n');
        }
        Map<String, Object> postal = postalOf(party);
        for (String key : List.of("street1", "street2", "postCode", "city")) {
            if (postal.get(key) != null) {
                content.append(postal.get(key)).append('\n');
            }
        }
        content.append('\n').append("Invoice ").append(bill.getBillNo())
                .append(" — amount due ").append(bill.getAmountDueValue()).append(' ')
                .append(bill.getAmountDueUnit()).append(", payment reference ").append(kid)
                .append(", due ").append(bill.getPeriodEnd().plusDays(14)).append('.').append('\n');
        for (AppliedBillingRate line : lines == null ? List.<AppliedBillingRate>of() : lines) {
            content.append("  ").append(line.getName()).append(": ")
                    .append(line.getAmountValue()).append(' ').append(line.getAmountUnit()).append('\n');
        }
        letter.put("content", content.toString());
        letter.put("invoiceMeta", Map.of(
                "kid", kid,
                "billNo", bill.getBillNo(),
                "amount", Map.of("value", bill.getAmountDueValue(), "unit", bill.getAmountDueUnit()),
                "dueDate", bill.getPeriodEnd().plusDays(14).toString()));
        return letter;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> postalOf(Map<String, Object> party) {
        if (party == null || !(party.get("contactMedium") instanceof List<?> media)) {
            return Map.of();
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> medium && "postalAddress".equals(medium.get("mediumType"))
                    && medium.get("characteristic") instanceof Map<?, ?> c) {
                return (Map<String, Object>) c;
            }
        }
        return Map.of();
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("unserializable distribution payload", e);
        }
    }

    /** The RELAY: drains pending ledger rows per tenant, exponential
     * backoff between tries, FAILED after the last one — never lost,
     * never blocking a billing run, always accountable. */
    @Scheduled(fixedDelayString = "${bss.billing.distribution-tick-ms:30000}")
    public void deliverTick() {
        if (!tickGuard.claim("bill-distribution", java.time.Duration.ofSeconds(60))) {
            return; // another replica is delivering — one paper bill, never two
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (var ignored = com.bss.billing.security.TenantContext.actAs(tenant.getId())) {
                    deliverTenant(tenant);
                } catch (Exception e) {
                    log.warn("distribution relay failed for tenant {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("bill-distribution");
        }
    }

    private void deliverTenant(TenantRegistry.TenantEntry tenant) {
        List<BillDistribution> due = ledger
                .findTop25ByTenantIdAndStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                        tenant.getId(), BillDistribution.PENDING, OffsetDateTime.now());
        for (BillDistribution row : due) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastUpdate(OffsetDateTime.now());
            try {
                // the row's channel picks the wire: consent rails speak their
                // own JSON; everything else is the partner's envelope
                if ("efaktura".equals(row.getChannel())) {
                    restClient.post().uri(einvoiceRailUrl + "/rfp")
                            .header("Content-Type", "application/json")
                            .body(row.getPayload()).retrieve().toBodilessEntity();
                } else if ("mailbox".equals(row.getChannel())) {
                    restClient.post().uri(mailboxRailUrl + "/letters")
                            .header("Content-Type", "application/json")
                            .body(row.getPayload()).retrieve().toBodilessEntity();
                } else {
                    restClient.post().uri(tenant.getBillDistributionUrl() + "/invoices")
                            .header("Authorization", "Bearer " + tenant.getBillDistributionToken())
                            .header("Content-Type", "application/json")
                            .body(Map.of(
                                    "billNo", row.getBillNo(),
                                    "format", row.getFormat(),
                                    "channel", row.getChannel(),
                                    "recipient", row.getRecipient(),
                                    "contentType", row.getContentType(),
                                    "payload", row.getPayload()))
                            .retrieve().toBodilessEntity();
                }
                row.setStatus(BillDistribution.SENT);
                row.setSentAt(OffsetDateTime.now());
                row.setLastError(null);
                // the audit answer, on the record: WHICH channel carried it
                events.publish("BillDistributedEvent", "billDistribution", Map.of(
                        "billId", row.getBillId(),
                        "billNo", row.getBillNo(),
                        "channel", row.getChannel(),
                        "relatedParty", List.of(Map.of(
                                "id", row.getRecipient(), "role", "customer"))),
                        tenant.getId());
                log.info("bill {} distributed: {} via {} (attempt {})", row.getBillNo(),
                        row.getFormat(), row.getChannel(), row.getAttempts());
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                row.setLastError(error.length() > 500 ? error.substring(0, 500) : error);
                if (row.getAttempts() >= maxAttempts) {
                    // the ops worklist — visible at /billDistribution, retryable
                    row.setStatus(BillDistribution.FAILED);
                    row.setNextAttemptAt(null);
                    log.warn("bill {} distribution FAILED after {} attempts: {}",
                            row.getBillNo(), row.getAttempts(), error);
                } else {
                    long backoff = retrySeconds * (1L << Math.min(row.getAttempts() - 1, 6));
                    row.setNextAttemptAt(OffsetDateTime.now().plusSeconds(Math.min(backoff, 3600)));
                    log.info("bill {} distribution attempt {} failed ({}) — retrying in {}s",
                            row.getBillNo(), row.getAttempts(), error, Math.min(backoff, 3600));
                }
            }
            ledger.save(row);
        }
    }

    /** The delivery ledger as a worklist (payloads stay out — they are
     * large and the partner already has or will get them). */
    public List<Map<String, Object>> ledgerView(String tenantId, String status) {
        List<BillDistribution> rows = status == null
                ? ledger.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId)
                : ledger.findTop100ByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        return rows.stream().map(r -> {
            Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
            map.put("id", r.getId());
            map.put("billNo", r.getBillNo());
            map.put("format", r.getFormat());
            map.put("channel", r.getChannel());
            map.put("status", r.getStatus());
            map.put("attempts", r.getAttempts());
            map.put("lastError", r.getLastError());
            map.put("createdAt", r.getCreatedAt().toString());
            map.put("sentAt", r.getSentAt() == null ? null : r.getSentAt().toString());
            map.put("buyerStatus", r.getBuyerStatus());
            map.put("buyerNote", r.getBuyerNote());
            map.put("respondedAt", r.getRespondedAt() == null ? null : r.getRespondedAt().toString());
            map.put("@type", "BillDistribution");
            return (Map<String, Object>) map;
        }).toList();
    }

    /**
     * THE BUYER ANSWERS: a Peppol Invoice Response (UBL ApplicationResponse,
     * BIS 3 Invoice Response) arriving from the access point. The response
     * code becomes the ledger row's buyer status, so one row tells the whole
     * story: sent -> accepted -> paid, or rejected with the buyer's words.
     * 'paid' here is the BUYER'S claim — the money is only real when the
     * bank's remittance says so; the two paths stay separate on purpose.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> applyInvoiceResponse(String tenantId, String xml) {
        Response response = parseInvoiceResponse(xml);
        List<BillDistribution> rows = ledger.findByTenantIdAndBillNo(tenantId, response.billNo());
        int updated = 0;
        for (BillDistribution row : rows) {
            if (!"einvoice".equalsIgnoreCase(row.getChannel())) {
                continue; // print jobs have no buyer protocol
            }
            row.setBuyerStatus(response.status());
            row.setBuyerNote(response.note());
            row.setRespondedAt(OffsetDateTime.now());
            row.setLastUpdate(OffsetDateTime.now());
            ledger.save(row);
            updated++;
            log.info("invoice response: {} is now '{}' by the buyer{}", row.getBillNo(),
                    response.status(), response.note() == null ? "" : " — " + response.note());
        }
        return Map.of("billNo", response.billNo(), "buyerStatus", response.status(),
                "updated", updated);
    }

    record Response(String billNo, String status, String note) {
    }

    /** UBL ApplicationResponse — namespace-agnostic, XXE-hardened (this
     * arrives from outside). The UNECE 4343 response codes become words. */
    Response parseInvoiceResponse(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String code = firstText(doc, "ResponseCode");
            String billNo = firstText(doc, "ID", "DocumentReference");
            if (code == null || billNo == null) {
                throw new com.bss.billing.exception.BadRequestException(
                        "an invoice response needs a ResponseCode and a DocumentReference/ID");
            }
            String status = switch (code) {
                case "AB" -> "acknowledged";
                case "IP" -> "inProcess";
                case "AP" -> "accepted";
                case "RE" -> "rejected";
                case "CA" -> "conditionallyAccepted";
                case "UQ" -> "underQuery";
                case "PD" -> "paid";
                default -> throw new com.bss.billing.exception.BadRequestException(
                        "unknown UNECE 4343 response code '" + code + "'");
            };
            return new Response(billNo, status, firstText(doc, "Description"));
        } catch (com.bss.billing.exception.BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new com.bss.billing.exception.BadRequestException(
                    "not a parseable ApplicationResponse: " + e.getMessage());
        }
    }

    private static String firstText(org.w3c.dom.Document doc, String localName) {
        org.w3c.dom.NodeList list = doc.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().trim();
    }

    /** First <localName> that sits under a <parentLocalName> element. */
    private static String firstText(org.w3c.dom.Document doc, String localName, String parentLocalName) {
        org.w3c.dom.NodeList parents = doc.getElementsByTagNameNS("*", parentLocalName);
        for (int i = 0; i < parents.getLength(); i++) {
            org.w3c.dom.NodeList list = ((org.w3c.dom.Element) parents.item(i))
                    .getElementsByTagNameNS("*", localName);
            if (list.getLength() > 0) {
                return list.item(0).getTextContent().trim();
            }
        }
        return null;
    }

    /** An admin's second chance for a FAILED row — back to pending, due now. */
    public Map<String, Object> retry(String tenantId, String id) {
        BillDistribution row = ledger.findById(id)
                .filter(r -> tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> com.bss.billing.exception.NotFoundException
                        .forResource("BillDistribution", id));
        row.setStatus(BillDistribution.PENDING);
        row.setNextAttemptAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        ledger.save(row);
        return Map.of("status", "pending", "billNo", row.getBillNo());
    }

    /** UBL 2.1 Invoice — the EN 16931 core wearing whatever customization
     * the PROFILE ROW declares; EHF, generic Peppol BIS and A-NZ are the
     * same skeleton with different rows. */
    /** UBL CreditNote-2 (type 381), the Peppol BIS / EHF shape: its own
     * number, a BillingReference to the invoice it reverses, credited
     * lines. The same profile row (CustomizationID/ProfileID) that
     * dresses invoices dresses their reversals. */
    public String creditNoteXml(String tenantId, com.bss.billing.entity.CreditNote note,
            List<Map<String, Object>> creditedLines) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        String format = tenant == null || tenant.getBillDistributionFormat() == null
                ? "ehf" : tenant.getBillDistributionFormat();
        Profile profile = profileOf(tenantId, format);
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<CreditNote xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2\"\n")
           .append("            xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"\n")
           .append("            xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");
        if (profile.customizationId() != null) {
            xml.append("  <cbc:CustomizationID>").append(profile.customizationId())
               .append("</cbc:CustomizationID>\n");
        }
        if (profile.profileId() != null) {
            xml.append("  <cbc:ProfileID>").append(profile.profileId()).append("</cbc:ProfileID>\n");
        }
        xml.append("  <cbc:ID>").append(note.getCreditNoteNo()).append("</cbc:ID>\n");
        xml.append("  <cbc:IssueDate>").append(note.getIssuedAt().toLocalDate()).append("</cbc:IssueDate>\n");
        xml.append("  <cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>\n");
        xml.append("  <cbc:DocumentCurrencyCode>").append(note.getAmountUnit()).append("</cbc:DocumentCurrencyCode>\n");
        // the law's load-bearing element: WHICH invoice this reverses
        xml.append("  <cac:BillingReference><cac:InvoiceDocumentReference><cbc:ID>")
           .append(note.getBillNo())
           .append("</cbc:ID></cac:InvoiceDocumentReference></cac:BillingReference>\n");
        xml.append("  <cac:AccountingSupplierParty><cac:Party><cac:PartyName><cbc:Name>")
           .append(tenantId).append("</cbc:Name></cac:PartyName></cac:Party></cac:AccountingSupplierParty>\n");
        xml.append("  <cac:AccountingCustomerParty><cac:Party><cac:PartyIdentification><cbc:ID>")
           .append(note.getOwnerPartyId()).append("</cbc:ID></cac:PartyIdentification></cac:Party></cac:AccountingCustomerParty>\n");
        xml.append("  <cac:LegalMonetaryTotal><cbc:PayableAmount currencyID=\"")
           .append(note.getAmountUnit()).append("\">")
           .append(note.getAmountValue()).append("</cbc:PayableAmount></cac:LegalMonetaryTotal>\n");
        int lineNo = 1;
        List<Map<String, Object>> ublLines = creditedLines == null || creditedLines.isEmpty()
                ? List.of(Map.of("name", note.getReason(), "amount", note.getAmountValue()))
                : creditedLines;
        for (Map<String, Object> line : ublLines) {
            xml.append("  <cac:CreditNoteLine><cbc:ID>").append(lineNo++).append("</cbc:ID>")
               .append("<cbc:CreditedQuantity unitCode=\"C62\">1</cbc:CreditedQuantity>")
               .append("<cbc:LineExtensionAmount currencyID=\"").append(note.getAmountUnit()).append("\">")
               .append(line.get("amount")).append("</cbc:LineExtensionAmount>")
               .append("<cac:Item><cbc:Name>").append(escape(String.valueOf(line.get("name"))))
               .append("</cbc:Name></cac:Item>")
               .append("</cac:CreditNoteLine>\n");
        }
        xml.append("</CreditNote>\n");
        return xml.toString();
    }

    /** A credit note rides the SAME distribution ledger as the invoice it
     * reverses — when the tenant is partner-wired for e-invoicing, the
     * kreditnota ships as structured XML too. Fail-open otherwise. */
    public void distributeCreditNote(String tenantId, com.bss.billing.entity.CreditNote note,
            List<Map<String, Object>> creditedLines) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || !"partner".equalsIgnoreCase(tenant.getBillDistributionProvider())
                || tenant.getBillDistributionUrl() == null
                || tenant.getBillDistributionUrl().isBlank()) {
            return;
        }
        BillDistribution row = new BillDistribution();
        row.setId(java.util.UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setBillId(note.getBillId());
        row.setBillNo(note.getCreditNoteNo());
        row.setFormat(tenant.getBillDistributionFormat() == null ? "ehf"
                : tenant.getBillDistributionFormat());
        row.setChannel("einvoice");
        row.setRecipient(note.getOwnerPartyId());
        row.setContentType("application/xml");
        row.setPayload(creditNoteXml(tenantId, note, creditedLines));
        row.setStatus(BillDistribution.PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(OffsetDateTime.now());
        row.setCreatedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        ledger.save(row);
    }

    private String ublOf(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines,
            Profile profile) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"\n")
           .append("         xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"\n")
           .append("         xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");
        if (profile.customizationId() != null) {
            xml.append("  <cbc:CustomizationID>").append(profile.customizationId())
               .append("</cbc:CustomizationID>\n");
        }
        if (profile.profileId() != null) {
            xml.append("  <cbc:ProfileID>").append(profile.profileId()).append("</cbc:ProfileID>\n");
        }
        xml.append("  <cbc:ID>").append(bill.getBillNo()).append("</cbc:ID>\n");
        xml.append("  <cbc:IssueDate>").append(bill.getPeriodEnd()).append("</cbc:IssueDate>\n");
        xml.append("  <cbc:DueDate>").append(bill.getPeriodEnd().plusDays(14)).append("</cbc:DueDate>\n");
        xml.append("  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>\n");
        xml.append("  <cbc:DocumentCurrencyCode>").append(bill.getAmountDueUnit()).append("</cbc:DocumentCurrencyCode>\n");
        if (profile.paymentReference()) {
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

    /**
     * UN/EDIFACT INVOIC (D96A shape) — the segment format legacy B2B
     * trading partners still exchange: UNB/UNH envelope, BGM+380 names
     * the invoice, DTM+137 the issue date, one LIN/IMD/MOA+203 triple
     * per line, MOA+77 the invoice total, UNT counts its own segments.
     */
    private String edifactOf(String tenantId, CustomerBill bill, List<AppliedBillingRate> lines) {
        String interchangeRef = bill.getBillNo().replaceAll("\\D", "");
        StringBuilder e = new StringBuilder();
        e.append("UNA:+.? '\n");
        e.append("UNB+UNOC:3+").append(edifactEscape(tenantId.toUpperCase())).append("+")
         .append(edifactEscape(bill.getOwnerPartyId())).append("+")
         .append(bill.getPeriodEnd().format(DateTimeFormatter.ofPattern("yyMMdd")))
         .append(":0000+").append(interchangeRef).append("'\n");
        int segments = 0;
        StringBuilder m = new StringBuilder();
        m.append("UNH+1+INVOIC:D:96A:UN'\n"); segments++;
        m.append("BGM+380+").append(edifactEscape(bill.getBillNo())).append("+9'\n"); segments++;
        m.append("DTM+137:").append(bill.getPeriodEnd().format(DateTimeFormatter.BASIC_ISO_DATE))
         .append(":102'\n"); segments++;
        m.append("NAD+SU+++").append(edifactEscape(tenantId.toUpperCase())).append("'\n"); segments++;
        m.append("NAD+BY+++").append(edifactEscape(bill.getOwnerPartyId())).append("'\n"); segments++;
        m.append("CUX+2:").append(bill.getAmountDueUnit()).append(":4'\n"); segments++;
        int lineNo = 1;
        for (AppliedBillingRate line : lines) {
            m.append("LIN+").append(lineNo++).append("'\n"); segments++;
            m.append("IMD+F++:::").append(edifactEscape(line.getName())).append("'\n"); segments++;
            m.append("MOA+203:").append(line.getAmountValue()).append("'\n"); segments++;
        }
        m.append("UNS+S'\n"); segments++;
        m.append("MOA+77:").append(bill.getAmountDueValue()).append("'\n"); segments++;
        segments++; // UNT counts itself
        m.append("UNT+").append(segments).append("+1'\n");
        e.append(m);
        e.append("UNZ+1+").append(interchangeRef).append("'\n");
        return e.toString();
    }

    /** EDIFACT release character: ? releases + : ' and itself. */
    private static String edifactEscape(String s) {
        return s == null ? "" : s.replace("?", "??").replace("+", "?+")
                .replace(":", "?:").replace("'", "?'");
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
