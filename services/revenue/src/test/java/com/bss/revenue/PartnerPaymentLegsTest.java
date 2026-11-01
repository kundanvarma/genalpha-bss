package com.bss.revenue;

import com.bss.revenue.service.RevenueService;
import com.bss.revenue.service.RevenueService.Leg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paying a partner invoice (#390 review): the payable is settled by what was
 * accrued, never by what was invoiced, so a partner that bills more than the
 * ledger recorded leaves a variance in the books — not a payable below zero.
 */
class PartnerPaymentLegsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void anInvoiceAboveTheAccrual_settlesThePayableAtTheAccrual_andBooksTheDifference() {
        List<Leg> legs = RevenueService.partnerPaymentLegs(d("42.50"), d("40.00"));
        assertThat(legs).containsExactly(
                new Leg("partner:payable", d("40.00"), null),
                new Leg("partner:cogs", d("2.50"), null),
                new Leg("cash", null, d("42.50")));
        BigDecimal debits = legs.stream().map(Leg::debit).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo("42.50");   // balanced against the cash credit
    }

    @Test
    void anInvoiceAtOrBelowTheAccrual_settlesOnlyWhatWasPaid() {
        assertThat(RevenueService.partnerPaymentLegs(d("38.00"), d("40.00"))).containsExactly(
                new Leg("partner:payable", d("38.00"), null),
                new Leg("cash", null, d("38.00")));
    }

    @Test
    void nothingAccrued_isAllVariance_andAnOlderEventWithoutAnAccrualSettlesAsBefore() {
        assertThat(RevenueService.partnerPaymentLegs(d("10.00"), BigDecimal.ZERO)).containsExactly(
                new Leg("partner:cogs", d("10.00"), null),
                new Leg("cash", null, d("10.00")));
        assertThat(RevenueService.partnerPaymentLegs(d("10.00"), null)).containsExactly(
                new Leg("partner:payable", d("10.00"), null),
                new Leg("cash", null, d("10.00")));
    }
}
