package com.bss.party.dto;

import com.bss.party.entity.DirectorySetting;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One exposure choice: per party, optionally per service. */
@JsonPropertyOrder({"id", "partyId", "serviceRef", "exposure", "secretNumber", "updatedAt"})
public record DirectorySettingView(
        String id,
        String partyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String serviceRef,
        String exposure,
        boolean secretNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL) String updatedAt) {

    public static DirectorySettingView of(DirectorySetting s) {
        return new DirectorySettingView(s.getId(), s.getPartyId(), s.getServiceRef(), s.getExposure(),
                s.isSecretNumber(), s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
    }
}
