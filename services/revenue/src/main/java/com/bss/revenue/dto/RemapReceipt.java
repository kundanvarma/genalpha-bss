package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The renamed account, and what it does and does not touch. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"key", "accountCode", "accountName", "configValue", "note"})
public record RemapReceipt(
        @JsonProperty("key") String key,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("configValue") BigDecimal configValue,
        @JsonProperty("note") String note) {

    public static RemapReceipt of(String key, String code, String name, BigDecimal configValue) {
        return new RemapReceipt(key, code, name, configValue,
                "applies to FUTURE postings — booked lines keep their snapshot");
    }
}
