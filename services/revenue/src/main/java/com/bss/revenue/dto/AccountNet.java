package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** Net movement on one revenue-family account over a period. */
@JsonPropertyOrder({"accountCode", "accountName", "net"})
public record AccountNet(
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("net") BigDecimal net) {
}
