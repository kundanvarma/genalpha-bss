package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.UnappliedRemittance;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.UnappliedRemittanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE RETURN PATH: the bank tells us money arrived. An ISO 20022
 * camt.054 credit notification (the shape OCR/KID files, SEPA credit
 * transfers and giro payments all reduce to) lands on the webhook; each
 * credit entry is matched to a bill by its PAYMENT REFERENCE — the same
 * digits the EHF invoice carried out in its PaymentID. A clean match
 * records the bank payment (TMF676) and settles the bill through the
 * SAME guarantee path a card uses. Anything unclear — unknown reference,
 * wrong amount, a bill that is not open — is NEVER guessed at: it parks
 * on the unapplied-cash worklist with its reason. Money is fail-closed.
 */
@Service
public class RemittanceService {

    private static final Logger log = LoggerFactory.getLogger(RemittanceService.class);

    private final CustomerBillRepository bills;
    private final UnappliedRemittanceRepository unapplied;
    private final CustomerBillService billService;
    private final DownstreamClients.PaymentClient payments;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;

    public RemittanceService(CustomerBillRepository bills, UnappliedRemittanceRepository unapplied,
            CustomerBillService billService, DownstreamClients.PaymentClient payments,
            DomainEventPublisher events,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.bills = bills;
        this.unapplied = unapplied;
        this.billService = billService;
        this.payments = payments;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    public record CreditEntry(String reference, BigDecimal amount, String currency, String bankRef) {
    }

    record ParsedFile(String batchRef, List<CreditEntry> entries) {
    }

    /** One bank file in, one honest accounting out — WHATEVER dialect the
     * bank speaks. The format is detected from the content (camt.054 XML,
     * Nets OCR giro fixed-width, BAI2 lockbox CSV); everything downstream —
     * matching, the settle guarantee, unapplied cash — is identical.
     * Caller has already authenticated the bank and put us inside the
     * tenant. */
    public Map<String, Object> ingest(String tenantId, String body) {
        String content = body == null ? "" : body.trim();
        ParsedFile file;
        if (content.startsWith("<")) {
            file = new ParsedFile(batchRefOf(content), parseCamt054(content));
        } else if (content.startsWith("NY")) {
            file = parseOcr(content);
        } else if (content.startsWith("01,")) {
            file = parseBai2(content);
        } else {
            throw new BadRequestException(
                    "unrecognized remittance format — camt.054 XML, Nets OCR ('NY…')"
                    + " and BAI2 lockbox ('01,…') are spoken here");
        }
        List<CreditEntry> entries = file.entries();
        String batchRef = file.batchRef();
        int applied = 0;
        int parked = 0;
        for (CreditEntry entry : entries) {
            // each entry commits alone: one bad line never blocks the batch
            boolean ok = Boolean.TRUE.equals(tx.execute(status -> apply(tenantId, batchRef, entry)));
            if (ok) {
                applied++;
            } else {
                parked++;
            }
        }
        log.info("remittance batch {}: {} applied, {} parked as unapplied cash", batchRef, applied, parked);
        return Map.of("batchRef", batchRef == null ? "" : batchRef,
                "entries", entries.size(), "applied", applied, "unapplied", parked);
    }

    private boolean apply(String tenantId, String batchRef, CreditEntry entry) {
        List<CustomerBill> matches = entry.reference() == null || entry.reference().isBlank()
                ? List.of()
                : bills.findByTenantIdAndPaymentReference(tenantId, entry.reference());
        if (matches.isEmpty()) {
            return park(tenantId, batchRef, entry, "no bill carries reference '"
                    + entry.reference() + "'");
        }
        if (matches.size() > 1) {
            return park(tenantId, batchRef, entry, "reference '" + entry.reference()
                    + "' is ambiguous (" + matches.size() + " bills)");
        }
        CustomerBill bill = matches.get(0);
        if (!CustomerBill.NEW.equals(bill.getState())) {
            return park(tenantId, batchRef, entry, "bill " + bill.getBillNo()
                    + " is '" + bill.getState() + "', not open");
        }
        if (bill.getAmountDueValue() == null
                || entry.amount().compareTo(bill.getAmountDueValue()) != 0) {
            return park(tenantId, batchRef, entry, "amount " + entry.amount() + " "
                    + entry.currency() + " does not match " + bill.getAmountDueValue()
                    + " due on " + bill.getBillNo());
        }
        // record the bank's money as a TMF676 payment, then settle through
        // the same door a card payment uses — every guarantee included
        // deterministic correlator from (batch, reference): a re-sent bank
        // file resolves to the SAME id, so it can never book twice
        String correlator = UUID.nameUUIDFromBytes(
                ((batchRef == null ? "" : batchRef) + "|" + entry.reference())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String paymentId = payments.recordExternal(bill.getOwnerPartyId(), entry.amount(),
                entry.currency(), "Bank transfer for " + bill.getBillNo(),
                entry.bankRef(), correlator);
        CustomerBillDto patch = new CustomerBillDto();
        patch.setState(CustomerBill.SETTLED);
        patch.setPayment(List.of(Map.of("id", paymentId, "@referredType", "Payment")));
        billService.settle(bill.getId(), patch);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("billNo", bill.getBillNo());
        view.put("amount", entry.amount() + " " + entry.currency());
        view.put("relatedParty", List.of(Map.of("id", bill.getOwnerPartyId(), "role", "customer")));
        view.put("@type", "RemittanceApplied");
        events.publish("RemittanceAppliedEvent", "remittance", view);
        log.info("remittance applied: {} settled by bank transfer (ref {})",
                bill.getBillNo(), entry.reference());
        return true;
    }

    private boolean park(String tenantId, String batchRef, CreditEntry entry, String reason) {
        UnappliedRemittance row = new UnappliedRemittance();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setBatchRef(batchRef);
        row.setReference(entry.reference());
        row.setAmountValue(entry.amount());
        row.setAmountUnit(entry.currency());
        row.setReason(reason);
        row.setReceivedAt(OffsetDateTime.now());
        unapplied.save(row);
        log.info("remittance parked as unapplied cash: {}", reason);
        return false;
    }

    /** The unapplied-cash worklist — the AR queue a human resolves. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> unappliedView(String tenantId) {
        return unapplied.findTop100ByTenantIdOrderByReceivedAtDesc(tenantId).stream()
                .map(r -> {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("id", r.getId());
                    map.put("batchRef", r.getBatchRef());
                    map.put("reference", r.getReference());
                    map.put("amount", Map.of("unit", r.getAmountUnit(), "value", r.getAmountValue()));
                    map.put("reason", r.getReason());
                    map.put("receivedAt", r.getReceivedAt().toString());
                    map.put("@type", "UnappliedRemittance");
                    return (Map<String, Object>) map;
                }).toList();
    }

    /** ISO 20022 camt.054: credit entries with their structured creditor
     * reference (the KID / RF slot). Namespace-agnostic so every camt.054
     * version variant parses; XXE hardened — this endpoint takes XML from
     * outside. */
    List<CreditEntry> parseCamt054(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList entryNodes = doc.getElementsByTagNameNS("*", "Ntry");
            List<CreditEntry> entries = new ArrayList<>();
            for (int i = 0; i < entryNodes.getLength(); i++) {
                Element entry = (Element) entryNodes.item(i);
                if (!"CRDT".equals(firstText(entry, "CdtDbtInd"))) {
                    continue; // debits are the bank's business, not AR's
                }
                Element amt = firstElement(entry, "Amt");
                if (amt == null || amt.getTextContent().isBlank()) {
                    continue;
                }
                String reference = null;
                Element refInf = firstElement(entry, "CdtrRefInf");
                if (refInf != null) {
                    reference = firstText(refInf, "Ref");
                }
                entries.add(new CreditEntry(reference,
                        new BigDecimal(amt.getTextContent().trim()),
                        amt.getAttribute("Ccy").isBlank() ? "EUR" : amt.getAttribute("Ccy"),
                        firstText(entry, "AcctSvcrRef")));
            }
            if (entries.isEmpty()) {
                throw new BadRequestException("no credit entries in the notification");
            }
            return entries;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("not a parseable camt.054 notification: " + e.getMessage());
        }
    }

    /**
     * Nets OCR giro (Norway) — the fixed-width 80-char file that KID
     * payments come home in. We read what AR needs from the spec's
     * "amount item 1" record (NY, service 09, transaction types 10-21,
     * record type 30): the amount in oere at positions 34-50 and the
     * right-adjusted KID at positions 51-75. Domestic giro means NOK.
     * The transmission start record (NY000010) names the batch.
     */
    ParsedFile parseOcr(String content) {
        String batchRef = null;
        List<CreditEntry> entries = new ArrayList<>();
        for (String raw : content.split("\r?\n")) {
            String line = raw.trim();
            if (line.length() < 80 || !line.startsWith("NY")) {
                continue;
            }
            if (line.startsWith("NY000010")) {
                batchRef = "OCR-" + line.substring(16, 23).trim();
                continue;
            }
            // amount item 1: service 09, transaction type 10-21, record 30
            if (!"09".equals(line.substring(2, 4)) || !"30".equals(line.substring(6, 8))) {
                continue;
            }
            int txType;
            try {
                txType = Integer.parseInt(line.substring(4, 6));
            } catch (NumberFormatException e) {
                continue;
            }
            if (txType < 10 || txType > 21) {
                continue;
            }
            String oere = line.substring(33, 50).trim();
            String kid = line.substring(50, 75).trim();
            if (oere.isEmpty() || kid.isEmpty()) {
                continue;
            }
            entries.add(new CreditEntry(kid,
                    new BigDecimal(oere).movePointLeft(2),
                    "NOK",
                    "ocr-" + line.substring(8, 15).trim()));
        }
        if (entries.isEmpty()) {
            throw new BadRequestException("no OCR amount items (NY09xx30) in the file");
        }
        return new ParsedFile(batchRef, entries);
    }

    /**
     * BAI2 lockbox — the US bank file: comma-separated records, amounts
     * in minor units, no decimals. The 16 records are the transaction
     * details: type code, amount, funds type, bank reference, customer
     * reference (the remittance reference we match on). Currency rides
     * the 02 group header; the 01 file header names the batch.
     */
    ParsedFile parseBai2(String content) {
        String batchRef = null;
        String currency = "USD";
        List<CreditEntry> entries = new ArrayList<>();
        for (String raw : content.split("\r?\n")) {
            String line = raw.trim();
            if (line.endsWith("/")) {
                line = line.substring(0, line.length() - 1);
            }
            String[] f = line.split(",", -1);
            if (f.length == 0) {
                continue;
            }
            switch (f[0]) {
                case "01" -> batchRef = "BAI2-" + (f.length > 5 ? f[3] + "-" + f[5] : "");
                case "02" -> {
                    if (f.length > 6 && !f[6].isBlank()) {
                        currency = f[6].trim();
                    }
                }
                case "16" -> {
                    if (f.length < 6 || f[2].isBlank() || f[5].isBlank()) {
                        continue;
                    }
                    entries.add(new CreditEntry(f[5].trim(),
                            new BigDecimal(f[2].trim()).movePointLeft(2),
                            currency,
                            f.length > 4 && !f[4].isBlank() ? "bai2-" + f[4].trim() : null));
                }
                default -> { }
            }
        }
        if (entries.isEmpty()) {
            throw new BadRequestException("no BAI2 transaction details (16 records) in the file");
        }
        return new ParsedFile(batchRef, entries);
    }

    private String batchRefOf(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList ids = doc.getElementsByTagNameNS("*", "MsgId");
            return ids.getLength() == 0 ? null : ids.item(0).getTextContent().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstElement(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String firstText(Element parent, String localName) {
        Element el = firstElement(parent, localName);
        return el == null ? null : el.getTextContent().trim();
    }
}
