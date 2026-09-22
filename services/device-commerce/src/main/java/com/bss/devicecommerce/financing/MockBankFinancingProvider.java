package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.entity.DeviceAgreement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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
    public FinancingQuote quote(FinancingTerms terms) {
        return new FinancingQuote(model(), FinancingMath.monthly(terms.principal(), terms.termMonths()),
                terms.principal(), "financier", settlementFee,
                "partner bank originates, owns the receivable, pays the operator upfront");
    }

    @Override
    public void originate(DeviceAgreement agreement, DeviceAgreementRequest request) {
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
    public EarlySettlementQuote earlySettlementQuote(DeviceAgreement agreement) {
        BigDecimal remaining = FinancingMath.remainingPrincipal(agreement);
        return EarlySettlementQuote.bank(model(), remaining.add(settlementFee), remaining, settlementFee,
                agreement.getFinancierRef(), agreement.getExternalAgreementNo());
    }

    @Override
    public FinancingSettlement settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        BigDecimal settlementAmount = earlySettlementQuote(agreement).amount();
        // the trade-in's graded value goes toward the bank's settlement;
        // whatever it does not cover is the customer's (or the program's).
        BigDecimal shortfall = settlementAmount.subtract(tradeInValue);
        return FinancingSettlement.bank(model(), settlementAmount, tradeInValue,
                shortfall.signum() > 0 ? shortfall : BigDecimal.ZERO,
                shortfall.signum() < 0 ? shortfall.negate() : BigDecimal.ZERO,
                agreement.getExternalAgreementNo(), "mock bank confirmed early settlement");
    }
}
