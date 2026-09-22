package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The swap: which accepted trade-in stands behind it (the body is optional so the refusal can say why). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwapRequest(String tradeInValuationId) {

    public static final SwapRequest EMPTY = new SwapRequest(null);
}
