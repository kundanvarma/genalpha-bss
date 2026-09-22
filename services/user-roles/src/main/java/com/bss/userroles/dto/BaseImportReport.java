package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The migration door's S6-style report: what landed, what already existed,
 * which offerings are missing BY NAME, what failed — and what this version
 * does not do, on its face.
 */
@JsonPropertyOrder({"@type", "tenantId", "rows", "imported", "alreadyPresent", "offeringMissing", "failed",
        "customers", "exceptions", "readyForCutover", "assumptions"})
public record BaseImportReport(@JsonProperty("@type") String type, String tenantId, int rows, int imported,
        int alreadyPresent, int offeringMissing, int failed, List<ImportedCustomer> customers,
        Exceptions exceptions, boolean readyForCutover, List<String> assumptions) {

    @JsonPropertyOrder({"externalRef", "partyId", "email", "temporaryPassword", "offeringName", "msisdn"})
    public record ImportedCustomer(String externalRef, String partyId, String email, String temporaryPassword,
            String offeringName, @JsonInclude(JsonInclude.Include.NON_NULL) String msisdn) {
    }

    @JsonPropertyOrder({"externalRef", "offeringName", "reason"})
    public record MissingOffering(String externalRef, String offeringName, String reason) {
    }

    @JsonPropertyOrder({"externalRef", "reason"})
    public record FailedRow(String externalRef, String reason) {
    }

    @JsonPropertyOrder({"offeringMissing", "alreadyPresent", "failed"})
    public record Exceptions(List<MissingOffering> offeringMissing, List<String> alreadyPresent,
            List<FailedRow> failed) {
    }

    public static BaseImportReport of(String tenantId, int rows, List<ImportedCustomer> imported,
            List<String> alreadyPresent, List<MissingOffering> offeringMissing, List<FailedRow> failed) {
        return new BaseImportReport("BaseImport", tenantId, rows, imported.size(), alreadyPresent.size(),
                offeringMissing.size(), failed.size(), imported,
                new Exceptions(offeringMissing, alreadyPresent, failed),
                offeringMissing.isEmpty() && failed.isEmpty(), List.of(
                        "idempotent per EMAIL: a re-run counts alreadyPresent and creates nothing",
                        "each customer gets a LOGIN with a temporary password — hand it over on cutover",
                        "the MSISDN rides the product as its supporting resource (the number the portal shows)",
                        "NOT in this version: open balances, SIM ICCIDs, port-in orchestration — book balances "
                                + "separately and port numbers in waves"));
    }
}
