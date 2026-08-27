package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
    public Map<String, Object> quote(Map<String, Object> terms) {
        BigDecimal principal = new BigDecimal(String.valueOf(terms.get("principal")));
        int months = Integer.parseInt(String.valueOf(terms.get("termMonths")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("monthlyAmount", FinancingMath.monthly(principal, months));
        out.put("totalCostOfOwnership", principal);
        out.put("titleHolder", "provider");
        out.put("note", "provider pays the operator upfront; the customer's schedule is the provider's");
        return out;
    }

    @Override
    public void originate(DeviceAgreement agreement, Map<String, Object> dto) {
        String paymentRef = agreement.getPaymentRef();
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new BadRequestException(
                    "BNPL financing requires the paymentRef of the checkout payment (BNPL method)");
        }
        Map<String, Object> payment = payments.payment(paymentRef);
        if (payment == null) {
            throw new BadRequestException("paymentRef '" + paymentRef
                    + "' does not resolve to a payment — BNPL origination refused");
        }
        agreement.setFinancierRef(payment.get("pspProvider") == null
                ? "bnpl-provider" : String.valueOf(payment.get("pspProvider")));
        agreement.setExternalAgreementNo(payment.get("correlatorId") == null
                ? paymentRef : String.valueOf(payment.get("correlatorId")));
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
    public Map<String, Object> earlySettlementQuote(DeviceAgreement agreement) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("amount", FinancingMath.remainingPrincipal(agreement));
        out.put("settlementDelegated", true);
        out.put("provider", agreement.getFinancierRef());
        out.put("note", "indicative only — the provider owns the schedule and quotes the binding figure");
        return out;
    }

    @Override
    public Map<String, Object> settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("settlementDelegated", true);
        out.put("provider", agreement.getFinancierRef());
        out.put("providerSettlementStatus", "settled");   // mock: the provider confirms
        out.put("tradeInValue", tradeInValue);
        out.put("note", "customer settles the provider; trade-in value credits the new purchase");
        return out;
    }
}
