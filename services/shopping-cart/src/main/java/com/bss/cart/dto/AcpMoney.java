package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** ACP money: the amount as a plain decimal string, the currency code. */
@JsonPropertyOrder({"amount", "currency"})
public record AcpMoney(String amount, String currency) {

    public static AcpMoney of(BigDecimal amount, String currency) {
        return new AcpMoney(amount.toPlainString(), currency);
    }

    public BigDecimal decimal() {
        return new BigDecimal(amount);
    }
}
