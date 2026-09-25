package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * One posting key of the tenant's editable chart of accounts.
 *
 * <p>{@code books} is what the key actually books, in business language. It
 * rides with the row because a posting key is an identifier and an identifier
 * is never a description — and because three channels would otherwise each
 * invent their own words for the same row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"key", "accountCode", "accountName", "configValue", "books", "setting", "postings"})
public record ChartRow(
        @JsonProperty("key") String key,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("configValue") BigDecimal configValue,
        @JsonProperty("books") String books,
        @JsonProperty("setting") String setting,
        @JsonProperty("postings") Long postings) {
}
