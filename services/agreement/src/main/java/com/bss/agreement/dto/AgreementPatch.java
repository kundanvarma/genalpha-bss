package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Back-office lifecycle: activate (the period starts) or terminate. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgreementPatch(String status) {
}
