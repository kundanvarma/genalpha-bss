package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * DeviceInstallmentRecordedEvent payload — the subsidy slice this instalment
 * unwinds from the IFRS 15 contract asset (revenue's subledger posts it).
 */
@JsonPropertyOrder({"agreementId", "installmentNo", "unwindAmount", "currency", "@type"})
public record DeviceInstallmentUnwind(String agreementId, int installmentNo, BigDecimal unwindAmount,
        String currency, @JsonProperty("@type") String type) {

    public static DeviceInstallmentUnwind of(String agreementId, int installmentNo, BigDecimal unwindAmount,
            String currency) {
        return new DeviceInstallmentUnwind(agreementId, installmentNo, unwindAmount, currency, "DeviceInstallment");
    }
}
