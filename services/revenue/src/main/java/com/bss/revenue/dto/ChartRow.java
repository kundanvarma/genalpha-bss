package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One posting key of the tenant's editable chart of accounts. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"key", "accountCode", "accountName", "configValue"})
public record ChartRow(
        @JsonProperty("key") String key,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("configValue") BigDecimal configValue) {
}
