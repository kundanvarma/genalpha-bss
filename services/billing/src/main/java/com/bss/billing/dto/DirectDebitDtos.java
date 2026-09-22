package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/** The direct-debit loop's files, claims and receipts. */
public final class DirectDebitDtos {

    private DirectDebitDtos() {
    }

    /** The bank's mandate batch: add (default) or delete records. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MandateFile(List<MandateRecord> records) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MandateRecord(String partyRef, String action, String accountRef) {
    }

    @JsonPropertyOrder({"registered", "cancelled", "skipped"})
    public record MandateFileReceipt(int registered, int cancelled, int skipped) {
    }

    /** One cycle claim as it goes to the rail — and as the run reports it. */
    @JsonPropertyOrder({"kid", "billNo", "partyRef", "accountRef", "amount", "dueDate"})
    public record Claim(String kid, String billNo, String partyRef, String accountRef, Money amount,
            String dueDate) {
    }

    @JsonPropertyOrder({"claims", "skipped", "sentClaims"})
    public record ClaimRunReceipt(int claims, int skipped, List<Claim> sentClaims) {
    }

    /** The settlement file home: the remittance accounting, then the claims it settled.
     * Also the SettlementReceivedEvent payload. */
    @JsonPropertyOrder({"remittance", "source", "settledClaims", "@type"})
    public record SettlementFileReceipt(@JsonUnwrapped RemittanceReceipt remittance, String source,
            List<ClaimView> settledClaims, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "partyId", "accountRef", "status", "registeredAt", "cancelledAt", "relatedParty",
            "@type"})
    public record MandateView(String id, String partyId, String accountRef, String status, String registeredAt,
            String cancelledAt, List<RelatedPartyRef> relatedParty, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "billId", "billNo", "kid", "amount", "status", "requestedAt", "settledAt", "@type"})
    public record ClaimView(String id, String billId, String billNo, String kid, Money amount, String status,
            String requestedAt, String settledAt, @JsonProperty("@type") String type) {
    }
}
