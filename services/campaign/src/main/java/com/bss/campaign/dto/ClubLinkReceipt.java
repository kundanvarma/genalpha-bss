package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** My code and the club it now counts for (empty when untied). */
@JsonPropertyOrder({"code", "clubOrgId"})
public record ClubLinkReceipt(String code, String clubOrgId) {
}
