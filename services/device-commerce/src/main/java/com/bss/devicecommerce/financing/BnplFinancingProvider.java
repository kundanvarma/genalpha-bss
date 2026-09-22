package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * BNPL: the provider paid the operator out at checkout through the payment
 * rail — origination here is only a VERIFICATION that the checkout payment
 * exists (payment component, machine call). The customer owes the provider,
 * settlement is delegated: an upgrade means the customer settles with the
 * provider (or the provider's settlement API does), never an operator
 * write-off.
 */
@Component
public class BnplFinancingProvider implements FinancingProvider {

    private final PaymentClient payments;

    public BnplFinancingProvider(PaymentClient payments) {
        this.payments = payments;
    }

    @Override
    public String model() {
        return DeviceAgreement.BNPL;
    }

    @Override
    public FinancingQuote quote(FinancingTerms terms) {
        return FinancingQuote.of(model(), FinancingMath.monthly(terms.principal(), terms.termMonths()),
                terms.principal(), "provider",
                "provider pays the operator upfront; the customer's schedule is the provider's");
    }

    @Override
    public void originate(DeviceAgreement agreement, DeviceAgreementRequest request) {
        String paymentRef = agreement.getPaymentRef();
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new BadRequestException(
                    "BNPL financing requires the paymentRef of the checkout payment (BNPL method)");
        }
        JsonNode payment = payments.payment(paymentRef);
        if (payment == null) {
            throw new BadRequestException("paymentRef '" + paymentRef
                    + "' does not resolve to a payment — BNPL origination refused");
        }
        agreement.setFinancierRef(payment.hasNonNull("pspProvider")
                ? payment.get("pspProvider").asText() : "bnpl-provider");
        agreement.setExternalAgreementNo(payment.hasNonNull("correlatorId")
                ? payment.get("correlatorId").asText() : paymentRef);
        agreement.setTitleHolder("provider");
        // provider paid at checkout: the payout IS the capture
        agreement.setPayoutReceivedAt(java.time.OffsetDateTime.now());
        agreement.setStatus(DeviceAgreement.ACTIVE);
    }

    @Override
    public void payoutReceived(DeviceAgreement agreement) {
        agreement.setPayoutReceivedAt(java.time.OffsetDateTime.now());
    }

    @Override
    public EarlySettlementQuote earlySettlementQuote(DeviceAgreement agreement) {
        return EarlySettlementQuote.delegated(model(), FinancingMath.remainingPrincipal(agreement),
                agreement.getFinancierRef(),
                "indicative only — the provider owns the schedule and quotes the binding figure");
    }

    @Override
    public FinancingSettlement settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        // mock: the provider confirms
        return FinancingSettlement.delegated(model(), agreement.getFinancierRef(), "settled", tradeInValue,
                "customer settles the provider; trade-in value credits the new purchase");
    }
}
