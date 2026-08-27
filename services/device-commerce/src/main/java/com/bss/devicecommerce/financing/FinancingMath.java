package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.entity.DeviceAgreement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared instalment arithmetic — the last part takes the rounding remainder,
 * so parts always sum to the principal. */
public final class FinancingMath {

    private FinancingMath() {
    }

    public static BigDecimal monthly(BigDecimal principal, int termMonths) {
        return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
    }

    /** Principal not yet covered by paid instalments (never negative). */
    public static BigDecimal remainingPrincipal(DeviceAgreement a) {
        BigDecimal paid = a.getMonthlyAmount().multiply(BigDecimal.valueOf(a.getInstallmentsPaid()));
        BigDecimal remaining = a.getPrincipal().subtract(paid);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining.setScale(2, RoundingMode.HALF_UP);
    }

    /** Share of the principal paid so far, in percent. */
    public static BigDecimal paidSharePct(DeviceAgreement a) {
        if (a.getPrincipal().signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal paid = a.getPrincipal().subtract(remainingPrincipal(a));
        return paid.multiply(BigDecimal.valueOf(100))
                .divide(a.getPrincipal(), 1, RoundingMode.HALF_UP);
    }
}
