package com.bss.billing.listen;

import com.bss.billing.entity.CustomerBill;
import com.bss.billing.repository.CreditNoteRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.TenantContext;
import com.bss.billing.service.CreditNoteService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The compensation half of TMF623: assurance minted the violation (and
 * enforced the cap); billing turns it into the credit note the contract
 * PRE-AGREED. In-process issue — no admin machine grant exists or is
 * needed, because nobody is deciding anything here: the deciding
 * happened when the SLA was signed. Idempotent per violation (the
 * reason carries the violation id, and the ledger is checked).
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class SlaViolationListener {

    private static final Logger log = LoggerFactory.getLogger(SlaViolationListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final CreditNoteService creditNotes;
    private final CreditNoteRepository creditNoteLedger;
    private final CustomerBillRepository bills;
    private final ObjectMapper objectMapper;

    public SlaViolationListener(CreditNoteService creditNotes, CreditNoteRepository creditNoteLedger,
            CustomerBillRepository bills, ObjectMapper objectMapper) {
        this.creditNotes = creditNotes;
        this.creditNoteLedger = creditNoteLedger;
        this.bills = bills;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.billing.assurance-topic:bss.assurance.events}", groupId = "billing-sla")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"SlaViolationEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> violation = envelope.get("event") instanceof Map<?, ?> event
                    && event.get("slaViolation") instanceof Map<?, ?> v
                    ? (Map<String, Object>) v : Map.of();
            if (!Boolean.TRUE.equals(violation.get("credited"))) {
                return; // breach recorded but capped — assurance said no credit
            }
            BigDecimal amount = new BigDecimal(String.valueOf(violation.getOrDefault("creditAmount", "0")));
            if (amount.signum() <= 0) {
                return;
            }
            String violationId = String.valueOf(violation.get("id"));
            String party = null;
            if (violation.get("relatedParty") instanceof java.util.List<?> parties
                    && !parties.isEmpty() && parties.get(0) instanceof Map<?, ?> ref) {
                party = String.valueOf(ref.get("id"));
            }
            if (party == null) {
                return;
            }
            String reason = "SLA violation " + violationId + " — resolution "
                    + violation.get("durationMinutes") + "m exceeded the promised "
                    + violation.get("thresholdMinutes") + "m on " + violation.get("affectedObject")
                    + " (agreement " + violation.get("agreementId") + ")";
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                if (creditNoteLedger.existsByTenantIdAndReason(tenantId, reason)) {
                    return; // at-least-once delivery, already compensated
                }
                CustomerBill bill = bills
                        .findFirstByTenantIdAndOwnerPartyIdOrderByPeriodEndDesc(tenantId, party)
                        .orElse(null);
                if (bill == null) {
                    log.warn("SLA credit for {} has no bill to land on (party {})", violationId, party);
                    return;
                }
                Map<String, Object> note = creditNotes.issue(bill.getId(),
                        Map.of("amount", amount, "reason", reason));
                log.info("SLA compensation: credit note {} ({} EUR) for violation {} on bill {}",
                        note.get("creditNoteNo"), amount, violationId, bill.getBillNo());
            }
        } catch (Exception e) {
            log.warn("billing: skipping unprocessable assurance event: {}", e.getMessage());
        }
    }
}
