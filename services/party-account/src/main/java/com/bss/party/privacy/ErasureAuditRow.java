package com.bss.party.privacy;

import com.bss.party.entity.ErasureRecord;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The audit trail row: who, whom, when — no personal data beyond the party reference. */
@JsonPropertyOrder({"id", "partyId", "executedBy", "executedAt"})
public record ErasureAuditRow(String id, String partyId, String executedBy, String executedAt) {

    public static ErasureAuditRow of(ErasureRecord r) {
        return new ErasureAuditRow(r.getId(), r.getPartyId(), r.getExecutedBy(), r.getExecutedAt().toString());
    }
}
