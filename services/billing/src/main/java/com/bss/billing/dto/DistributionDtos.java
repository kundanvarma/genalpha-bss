package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The delivery ledger's faces and the rails' payloads. */
public final class DistributionDtos {

    private DistributionDtos() {
    }

    /** One ledger row as the worklist shows it (payloads stay out). */
    @JsonPropertyOrder({"id", "billNo", "format", "channel", "status", "attempts", "lastError", "createdAt",
            "sentAt", "buyerStatus", "buyerNote", "respondedAt", "@type"})
    public record LedgerRow(String id, String billNo, String format, String channel, String status, int attempts,
            String lastError, String createdAt, String sentAt, String buyerStatus, String buyerNote,
            String respondedAt, @JsonProperty("@type") String type) {
    }

    /** The buyer's answer landed on the ledger. */
    @JsonPropertyOrder({"billNo", "buyerStatus", "updated"})
    public record InvoiceResponseReceipt(String billNo, String buyerStatus, int updated) {
    }

    /** A failed row is pending again, due now. */
    @JsonPropertyOrder({"status", "billNo"})
    public record RetryReceipt(String status, String billNo) {
    }

    /** The e-invoice rail's request-for-payment: the KID IS the bill's payment reference. */
    @JsonPropertyOrder({"kid", "billNo", "aliasRef", "partyRef", "amount", "issueDate", "dueDate"})
    public record RequestForPayment(String kid, String billNo, String aliasRef, String partyRef, Money amount,
            String issueDate, String dueDate) {
    }

    /** The digital-mailbox letter: honest content, the provider does the addressing. */
    @JsonPropertyOrder({"partyRef", "subject", "content", "invoiceMeta"})
    public record Letter(String partyRef, String subject, String content, InvoiceMeta invoiceMeta) {
    }

    @JsonPropertyOrder({"kid", "billNo", "amount", "dueDate"})
    public record InvoiceMeta(String kid, String billNo, Money amount, String dueDate) {
    }
}
