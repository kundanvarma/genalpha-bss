package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** One part lands: the authorized payment covering this installment. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallmentPaymentRequest(List<PaymentRef> payment) {

    public String paymentId() {
        return payment == null || payment.isEmpty() || payment.get(0) == null ? null : payment.get(0).id();
    }
}
