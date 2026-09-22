package com.bss.party.privacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The eraser's report: what each service deleted, what the law holds, and —
 * once the audit row exists — its id. The stored report is the version before
 * {@link #withAudit(String)}; the wire answer is the version after.
 */
@JsonPropertyOrder({"partyId", "status", "executedBy", "executedAt", "categories", "auditRecordId"})
public record ErasureReport(
        String partyId,
        String status,
        String executedBy,
        String executedAt,
        List<JsonNode> categories,
        @JsonInclude(JsonInclude.Include.NON_NULL) String auditRecordId) {

    public ErasureReport withAudit(String auditRecordId) {
        return new ErasureReport(partyId, status, executedBy, executedAt, categories, auditRecordId);
    }
}
