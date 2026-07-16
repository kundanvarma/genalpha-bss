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

    /** One bank notification in, one honest accounting out. Caller has
     * already authenticated the bank and put us inside the tenant. */
    public Map<String, Object> ingest(String tenantId, String xml) {
        List<CreditEntry> entries = parseCamt054(xml);
        String batchRef = batchRefOf(xml);
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
