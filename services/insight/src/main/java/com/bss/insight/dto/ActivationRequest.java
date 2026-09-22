package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Push an audience to an ad platform's Custom Audience: which one, seed or suppress, where. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivationRequest(String externalAudienceId, String mode, String destination) {
}
