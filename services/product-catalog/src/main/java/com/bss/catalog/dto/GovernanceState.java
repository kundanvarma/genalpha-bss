package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The governance state stored beside a ProductOffering (its {@code governance_json}
 * column) and unwrapped into the {@link LaunchDecision} view. A plain struct: the
 * doors set what they learn, in the order the flow happens — request, approve,
 * reject, hold, resume, launch, unlaunch, void, expire. Keys this version does not
 * know (an older row, a future one) survive in {@code extensions}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"requestedAt", "requestedBy", "note", "origin", "readiness", "substanceHash",
        "approvedAt", "approvedBy", "approvalNote", "envelope",
        "rejectedAt", "rejectedBy", "rejectedReason",
        "heldFrom", "heldAt", "heldBy", "holdReason", "holdUntil", "heldValidFrom", "heldStatus",
        "resumedAt", "resumedBy", "launchedAt", "launchedBy", "launchedChannels",
        "unlaunchedAt", "unlaunchedBy", "unlaunchEnd", "voidedAt", "voidedReason", "expiredAt"})
public final class GovernanceState {

    public String requestedAt;
    public String requestedBy;
    public String note;
    public String origin;
    public List<ReadinessItem> readiness;
    public String substanceHash;
    public String approvedAt;
    public String approvedBy;
    public String approvalNote;
    public EnvelopeRef envelope;
    public String rejectedAt;
    public String rejectedBy;
    public String rejectedReason;
    public String heldFrom;
    public String heldAt;
    public String heldBy;
    public String holdReason;
    public String holdUntil;
    public String heldValidFrom;
    public String heldStatus;
    public String resumedAt;
    public String resumedBy;
    public String launchedAt;
    public String launchedBy;
    public List<String> launchedChannels;
    public String unlaunchedAt;
    public String unlaunchedBy;
    public String unlaunchEnd;
    public String voidedAt;
    public String voidedReason;
    public String expiredAt;

    private final Map<String, Object> extensions = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtension(String key, Object value) {
        extensions.put(key, value);
    }

    @JsonAnyGetter
    Map<String, Object> extensions() {
        return extensions;
    }

    /** The readiness list, never null. */
    public List<ReadinessItem> readinessOrEmpty() {
        return readiness == null ? List.of() : readiness;
    }
}
