package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.entity.DeviceAgreement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
    public Map<String, Object> quote(Map<String, Object> terms) {
        BigDecimal principal = new BigDecimal(String.valueOf(terms.get("principal")));
        int months = Integer.parseInt(String.valueOf(terms.get("termMonths")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("monthlyAmount", FinancingMath.monthly(principal, months));
        out.put("totalCostOfOwnership", principal);
        out.put("titleHolder", "operator");
        out.put("note", "0% instalments on the operator's own book");
        return out;
    }

    @Override
    public void originate(DeviceAgreement agreement, Map<String, Object> dto) {
        agreement.setTitleHolder(agreement.getTitleHolder() == null ? "operator" : agreement.getTitleHolder());
        agreement.setStatus(DeviceAgreement.ACTIVE);
    }

    @Override
    public void payoutReceived(DeviceAgreement agreement) {
        // no financier: nothing pays the operator out but the customer, monthly
    }

    @Override
    public Map<String, Object> earlySettlementQuote(DeviceAgreement agreement) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("amount", FinancingMath.remainingPrincipal(agreement));
        out.put("fee", BigDecimal.ZERO);
        out.put("note", "remaining instalments at face value — the operator holds the book");
        return out;
    }

    @Override
    public Map<String, Object> settle(DeviceAgreement agreement, BigDecimal tradeInValue) {
        BigDecimal remaining = FinancingMath.remainingPrincipal(agreement);
        // swap economics: remaining instalments write off against the graded
        // device coming in; a shortfall is the program's cost, a surplus is
        // credit toward the new device.
        BigDecimal writeOff = remaining.subtract(tradeInValue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financingModel", model());
        out.put("remainingPrincipal", remaining);
        out.put("tradeInValue", tradeInValue);
        out.put("writeOff", writeOff.signum() > 0 ? writeOff : BigDecimal.ZERO);
        out.put("customerCredit", writeOff.signum() < 0 ? writeOff.negate() : BigDecimal.ZERO);
        return out;
    }
}
