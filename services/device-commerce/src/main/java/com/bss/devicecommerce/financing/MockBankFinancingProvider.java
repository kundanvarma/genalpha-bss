package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.entity.DeviceAgreement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THIRD_PARTY_LOAN, in-process mock for demos/e2e: the partner bank
 * approves at origination, owns title and receivable, and pays the
 * operator out upfront (the service records the payout through the same
 * seam a real bank's webhook would hit — deterministic, no sleeping).
 * Early settlement is the bank's quote: remaining principal + a flat fee.
 * A real bank adapter replaces this class, nothing else.
 */
@Component
public class MockBankFinancingProvider implements FinancingProvider {

    private final BigDecimal settlementFee;

    public MockBankFinancingProvider(
            @Value("${bss.device.early-settlement-fee:49}") BigDecimal settlementFee) {
        this.settlementFee = settlementFee;
    }

    @Override
    public String model() {
        return DeviceAgreement.THIRD_PARTY_LOAN;
    }

    @Override
    public Map<String, Object> quote(Map<String, Object> terms) {
        BigDecimal principal = new BigDecimal(String.valueOf(terms.get("principal")));
        int months = Integer.parseInt(String.valueOf(terms.get("termMonths")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("monthlyAmount", FinancingMath.monthly(principal, months));
        out.put("totalCostOfOwnership", principal);
        out.put("titleHolder", "financier");
        out.put("earlySettlementFee", settlementFee);
        out.put("note", "partner bank originates, owns the receivable, pays the operator upfront");
        return out;
    }

    @Override
    public void originate(DeviceAgreement agreement, Map<String, Object> dto) {
        agreement.setFinancierRef(agreement.getFinancierRef() == null
                ? "mock-bank" : agreement.getFinancierRef());
        agreement.setExternalAgreementNo("MB-" + agreement.getId().substring(0, 8).toUpperCase());
        agreement.setTitleHolder("financier");
        agreement.setStatus(DeviceAgreement.ACTIVE);
    }

    @Override
    public void payoutReceived(DeviceAgreement agreement) {
        agreement.setPayoutReceivedAt(java.time.OffsetDateTime.now());
    }

    @Override
    public Map<String, Object> earlySettlementQuote(DeviceAgreement agreement) {
        BigDecimal remaining = FinancingMath.remainingPrincipal(agreement);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("amount", remaining.add(settlementFee));
        out.put("remainingPrincipal", remaining);
        out.put("fee", settlementFee);
        out.put("financierRef", agreement.getFinancierRef());
        out.put("externalAgreementNo", agreement.getExternalAgreementNo());
        return out;
    }

    @Override
    public Map<String, Object> settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        Map<String, Object> quote = earlySettlementQuote(agreement);
        BigDecimal settlementAmount = (BigDecimal) quote.get("amount");
        // the trade-in's graded value goes toward the bank's settlement;
        // whatever it does not cover is the customer's (or the program's).
        BigDecimal shortfall = settlementAmount.subtract(tradeInValue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("settlementAmount", settlementAmount);
        out.put("tradeInValue", tradeInValue);
        out.put("shortfall", shortfall.signum() > 0 ? shortfall : BigDecimal.ZERO);
        out.put("customerCredit", shortfall.signum() < 0 ? shortfall.negate() : BigDecimal.ZERO);
        out.put("externalAgreementNo", agreement.getExternalAgreementNo());
        out.put("note", "mock bank confirmed early settlement");
        return out;
    }
}
