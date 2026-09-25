package com.bss.billing;

import com.bss.billing.dto.BillSituation;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.service.BillSituations;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The precedence a bill's situation is decided by. Pure facts in, one answer
 * out, so every combination that changes the answer is pinned here rather
 * than discovered on a screen.
 *
 * <p>The case that matters most to the operator is
 * {@link #anArrangementStopsABillReadingAsOverdue()}: an approved extension
 * must change what every channel says, immediately.
 */
class BillSituationsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-25");
    private static final LocalDate LAST_WEEK = LocalDate.parse("2026-09-18");
    private static final LocalDate NEXT_WEEK = LocalDate.parse("2026-10-02");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final BigDecimal FORTY = new BigDecimal("40.00");

    private static BillSituations.Facts facts(String state, BigDecimal due, BigDecimal allocated,
            LocalDate dueDate, boolean dispute, LocalDate promise) {
        return new BillSituations.Facts(state, due, allocated, "NOK", dueDate, TODAY, dispute, promise);
    }

    // ---- the top of the ladder: nothing outranks these ----

    @Test
    void writtenOffOutranksEverything() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.WRITTEN_OFF, HUNDRED, BigDecimal.ZERO, LAST_WEEK, true, NEXT_WEEK));
        assertThat(s.value()).isEqualTo(BillSituation.WRITTEN_OFF);
        assertThat(s.reason()).contains("no longer collected");
    }

    @Test
    void aSettledBillIsPaidEvenAfterItsDueDate() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.SETTLED, HUNDRED, HUNDRED, LAST_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.PAID);
        assertThat(s.outstanding().value()).isEqualByComparingTo("0");
    }

    @Test
    void fullyAllocatedIsPaidEvenWhenTheStateLags() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.PARTIALLY_PAID, HUNDRED, HUNDRED, LAST_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.PAID);
    }

    @Test
    void anOpenDisputeIsNotChasedEvenWhenLate() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, true, null));
        assertThat(s.value()).isEqualTo(BillSituation.DISPUTED);
        assertThat(s.outstanding().value()).isEqualByComparingTo("100.00");
    }

    @Test
    void aDisputeOutranksAnArrangement() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, true, NEXT_WEEK));
        assertThat(s.value()).isEqualTo(BillSituation.DISPUTED);
        // the promise is still shown, because the customer was given one
        assertThat(s.arrangementUntil()).isEqualTo(NEXT_WEEK);
    }

    // ---- the rung the UX paper is about ----

    @Test
    void anArrangementStopsABillReadingAsOverdue() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, false, NEXT_WEEK));
        assertThat(s.value()).isEqualTo(BillSituation.ARRANGEMENT);
        assertThat(s.reason()).isEqualTo("Payment arrangement in place until 2026-10-02");
        assertThat(s.originalDueDate()).isEqualTo(LAST_WEEK);
        assertThat(s.currentDueDate()).isEqualTo(NEXT_WEEK);
        assertThat(s.arrangementUntil()).isEqualTo(NEXT_WEEK);
    }

    @Test
    void anArrangementDueTodayStillHolds() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, false, TODAY));
        assertThat(s.value()).isEqualTo(BillSituation.ARRANGEMENT);
    }

    @Test
    void anExpiredArrangementLetsTheBillGoOverdueAgain() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, false, LAST_WEEK.minusDays(1)));
        assertThat(s.value()).isEqualTo(BillSituation.OVERDUE);
        assertThat(s.arrangementUntil()).isNull();
    }

    @Test
    void anArrangementOnABillNotYetDueIsStillAnArrangement() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, NEXT_WEEK, false, NEXT_WEEK.plusDays(7)));
        assertThat(s.value()).isEqualTo(BillSituation.ARRANGEMENT);
    }

    // ---- lateness, and the calm states ----

    @Test
    void owingPastTheDueDateIsOverdue() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, LAST_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.OVERDUE);
        assertThat(s.reason()).isEqualTo("Overdue since 2026-09-18");
    }

    @Test
    void partlyPaidAndLateIsStillOverdue() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.PARTIALLY_PAID, HUNDRED, FORTY, LAST_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.OVERDUE);
        assertThat(s.outstanding().value()).isEqualByComparingTo("60.00");
    }

    @Test
    void dueTodayIsNotYetLate() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, TODAY, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.OUTSTANDING);
    }

    @Test
    void partlyPaidAndNotYetDueIsPartiallyPaid() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.PARTIALLY_PAID, HUNDRED, FORTY, NEXT_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.PARTIALLY_PAID);
        assertThat(s.outstanding().value()).isEqualByComparingTo("60.00");
    }

    @Test
    void theStoredPartlyPaidStateCountsEvenWithoutAnItemisedAmount() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.PARTIALLY_PAID, HUNDRED, BigDecimal.ZERO, NEXT_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.PARTIALLY_PAID);
    }

    @Test
    void owingAndNotYetDueIsTheCalmState() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, NEXT_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.OUTSTANDING);
        assertThat(s.reason()).isEqualTo("Outstanding — due 2026-10-02");
    }

    @Test
    void aNilBillIsIssuedRatherThanPaid() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, BigDecimal.ZERO, BigDecimal.ZERO, NEXT_WEEK, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.ISSUED);
        assertThat(s.reason()).contains("nothing to collect");
    }

    // ---- bills raised before due dates existed ----

    @Test
    void aBillWithNoDueDateIsNeverLate() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, null, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.OUTSTANDING);
        assertThat(s.originalDueDate()).isNull();
        assertThat(s.reason()).contains("the tenant's payment term");
    }

    @Test
    void aBillWithNoDueDateStillHonoursAnArrangement() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, BigDecimal.ZERO, null, false, NEXT_WEEK));
        assertThat(s.value()).isEqualTo(BillSituation.ARRANGEMENT);
        assertThat(s.currentDueDate()).isEqualTo(NEXT_WEEK);
    }

    // ---- the answer always carries what a screen needs to explain itself ----

    @Test
    void everySituationCarriesItsCurrencyAndDates() {
        BillSituation s = BillSituations.of(
                facts(CustomerBill.NEW, HUNDRED, FORTY, LAST_WEEK, false, null));
        assertThat(s.outstanding().unit()).isEqualTo("NOK");
        assertThat(s.originalDueDate()).isEqualTo(LAST_WEEK);
        assertThat(s.currentDueDate()).isEqualTo(LAST_WEEK);
        assertThat(s.type()).isEqualTo("BillSituation");
    }

    @Test
    void nullAmountsAreReadAsZeroRatherThanThrowing() {
        BillSituation s = BillSituations.of(
                new BillSituations.Facts(CustomerBill.NEW, null, null, "NOK", NEXT_WEEK, TODAY, false, null));
        assertThat(s.value()).isEqualTo(BillSituation.ISSUED);
        assertThat(s.outstanding().value()).isEqualByComparingTo("0");
    }
}
