package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * The governance view of one offering — what every governance door answers and
 * what the approvals desk lists. The offering's own facts first (always present,
 * null when unknown), then the stored {@link GovernanceState} unwrapped, then
 * the readiness ticks, the ledger trail and whether the caller may approve.
 */
@JsonPropertyOrder({"id", "name", "lifecycleStatus", "validFrom", "validTo", "governanceState", "mode",
        "holdUntil", "approvalExpiresAt", "channel", "lastUpdate", "state", "readiness", "ledger", "canApprove"})
public record LaunchDecision(String id, String name, String lifecycleStatus, String validFrom, String validTo,
        String governanceState, String mode, String holdUntil, String approvalExpiresAt, List<EntityRef> channel,
        String lastUpdate, @JsonUnwrapped GovernanceState state, List<ReadinessItem> readiness,
        List<LedgerLine> ledger, boolean canApprove) {
}
