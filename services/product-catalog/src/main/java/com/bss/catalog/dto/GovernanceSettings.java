package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** The tenant's launch-governance settings as the consoles read them. */
@JsonPropertyOrder({"mode", "aiProposals", "readiness", "canApprove", "channels"})
public record GovernanceSettings(String mode, String aiProposals, List<ReadinessItem> readiness, boolean canApprove,
        List<Map<String, String>> channels) {
}
