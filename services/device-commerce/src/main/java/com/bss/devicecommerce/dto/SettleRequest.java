package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Early termination: the ETF collected, if any (the body is optional). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettleRequest(BigDecimal etfAmount) {

    public static final SettleRequest EMPTY = new SettleRequest(null);
}
