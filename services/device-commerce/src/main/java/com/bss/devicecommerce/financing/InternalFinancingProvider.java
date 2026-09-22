package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.entity.DeviceAgreement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * OPERATOR_BOOK: the operator carries the receivable and the schedule lives
 * HERE, on the agreement (installmentsPaid of termMonths). Billing's
 * instalment-plan API splits an existing unpaid bill — origination-time
 * device financing has no bill yet, so the schedule stays local; feeding
 * the monthly part into the bill run is the documented follow-up seam.
 * IFRS 15 subsidy math applies in full to this model (revenue's listener).
 */
@Component
public class InternalFinancingProvider implements FinancingProvider {

    @Override
    public String model() {
        return DeviceAgreement.OPERATOR_BOOK;
    }

    @Override
    public FinancingQuote quote(FinancingTerms terms) {
        return FinancingQuote.of(model(), FinancingMath.monthly(terms.principal(), terms.termMonths()),
                terms.principal(), "operator", "0% instalments on the operator's own book");
    }

    @Override
    public void originate(DeviceAgreement agreement, DeviceAgreementRequest request) {
        agreement.setTitleHolder(agreement.getTitleHolder() == null ? "operator" : agreement.getTitleHolder());
        agreement.setStatus(DeviceAgreement.ACTIVE);
    }

    @Override
    public void payoutReceived(DeviceAgreement agreement) {
        // no financier: nothing pays the operator out but the customer, monthly
    }

    @Override
    public EarlySettlementQuote earlySettlementQuote(DeviceAgreement agreement) {
        return EarlySettlementQuote.operatorBook(model(), FinancingMath.remainingPrincipal(agreement),
                BigDecimal.ZERO, "remaining instalments at face value — the operator holds the book");
    }

    @Override
    public FinancingSettlement settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        BigDecimal remaining = FinancingMath.remainingPrincipal(agreement);
        // swap economics: remaining instalments write off against the graded
        // device coming in; a shortfall is the program's cost, a surplus is
        // credit toward the new device.
        BigDecimal writeOff = remaining.subtract(tradeInValue);
        return FinancingSettlement.writeOff(model(), remaining, tradeInValue,
                writeOff.signum() > 0 ? writeOff : BigDecimal.ZERO,
                writeOff.signum() < 0 ? writeOff.negate() : BigDecimal.ZERO);
    }
}
