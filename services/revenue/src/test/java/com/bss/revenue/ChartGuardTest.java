package com.bss.revenue;

import com.bss.revenue.dto.ChangeFinding;
import com.bss.revenue.entity.AccountMapping;
import com.bss.revenue.entity.FinancialConfigChange;
import com.bss.revenue.repository.JournalLineRepository;
import com.bss.revenue.service.ChartGuard;
import com.bss.revenue.service.ConfigChangeWords;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rules that stand between a keystroke and the tenant's books, without a
 * fleet. Every input combination that changes the answer is here, because the
 * browser suite proves the ladder works and this proves the ladder is right.
 */
class ChartGuardTest {

    private final JournalLineRepository lines = mock(JournalLineRepository.class);
    private final ChartGuard guard = new ChartGuard(lines);

    private static AccountMapping live(String code, String name, String value) {
        AccountMapping m = new AccountMapping();
        m.setTenantId("genalpha");
        m.setMappingKey("rate:recurringCharge");
        m.setAccountCode(code);
        m.setAccountName(name);
        m.setConfigValue(value == null ? null : new BigDecimal(value));
        return m;
    }

    private static List<String> messages(List<ChangeFinding> found, String severity) {
        return found.stream().filter(f -> severity.equals(f.severity())).map(ChangeFinding::message).toList();
    }

    @Test
    void anAccountCarryingPostingsCannotBeLeftWithoutACode() {
        AccountMapping m = live("4000", "Service revenue", null);
        List<ChangeFinding> found = guard.check("rate:recurringCharge", m, "  ", "Service revenue", null, 1482);
        assertThat(ChartGuard.blocked(found)).isTrue();
        assertThat(messages(found, ChangeFinding.BLOCKS).get(0))
                .contains("needs a code")
                .contains("1,482")      // the postings are named, not merely counted
                .contains("4000");
    }

    @Test
    void anAccountCodeAGeneralLedgerCannotReadIsRefused() {
        AccountMapping m = live("4000", "Service revenue", null);
        assertThat(ChartGuard.blocked(
                guard.check("rate:recurringCharge", m, "40 00, revenue", "Service revenue", null, 0))).isTrue();
        assertThat(ChartGuard.blocked(
                guard.check("rate:recurringCharge", m, "4000-NEW", "Service revenue", null, 0))).isFalse();
    }

    @Test
    void anAccountCannotLoseItsName() {
        AccountMapping m = live("4000", "Service revenue", null);
        assertThat(ChartGuard.blocked(guard.check("rate:recurringCharge", m, "4000", "", null, 3))).isTrue();
    }

    @Test
    void aRateNeverGoesNegativeAndVatNeverExceedsAHundred() {
        AccountMapping tax = live("2700", "VAT payable", "25");
        assertThat(ChartGuard.blocked(
                guard.check("tax", tax, "2700", "VAT payable", new BigDecimal("-1"), 0))).isTrue();
        assertThat(ChartGuard.blocked(
                guard.check("tax", tax, "2700", "VAT payable", new BigDecimal("101"), 0))).isTrue();
        assertThat(ChartGuard.blocked(
                guard.check("tax", tax, "2700", "VAT payable", new BigDecimal("0"), 0))).isFalse();
        assertThat(ChartGuard.blocked(
                guard.check("tax", tax, "2700", "VAT payable", new BigDecimal("25"), 0))).isFalse();
    }

    @Test
    void movingAUsedAccountWarnsAboutTheBookedLinesItLeavesBehind() {
        AccountMapping m = live("4000", "Service revenue", null);
        List<ChangeFinding> found = guard.check("rate:recurringCharge", m, "3000", "Service revenue", null, 2);
        assertThat(ChartGuard.blocked(found)).isFalse();
        assertThat(messages(found, ChangeFinding.WARNS).get(0))
                .contains("4000").contains("3000").contains("born with");
    }

    @Test
    void anUnusedAccountMovesWithoutACeremony() {
        AccountMapping m = live("4000", "Service revenue", null);
        assertThat(guard.check("rate:recurringCharge", m, "3000", "Service revenue", null, 0)).isEmpty();
    }

    @Test
    void aChangeThatMovedUnderneathIsSeen() {
        AccountMapping m = live("4000", "Service revenue", "25");
        assertThat(guard.movedUnder(m, "4000", "Service revenue", new BigDecimal("25.00"))).isFalse();
        assertThat(guard.movedUnder(m, "4000", "Service revenue", new BigDecimal("24"))).isTrue();
        assertThat(guard.movedUnder(m, "4001", "Service revenue", new BigDecimal("25"))).isTrue();
        assertThat(guard.movedUnder(m, "4000", "Revenue", new BigDecimal("25"))).isTrue();
    }

    @Test
    void anAccountWithNoCodeIsUsedByNothing() {
        when(lines.countByTenantIdAndAccountCode(anyString(), anyString())).thenReturn(7L);
        assertThat(guard.postingsUsing("genalpha", null)).isZero();
        assertThat(guard.postingsUsing("genalpha", "4000")).isEqualTo(7L);
    }

    @Test
    void theChangeSaysWhatItDoesInBusinessLanguage() {
        FinancialConfigChange row = new FinancialConfigChange();
        row.setPostingKey("tax");
        row.setCurrentCode("2700");
        row.setCurrentName("VAT payable");
        row.setCurrentValue(new BigDecimal("25"));
        row.setProposedCode("2701");
        row.setProposedName("VAT payable");
        row.setProposedValue(new BigDecimal("24.000000"));
        row.setState(FinancialConfigChange.VALIDATED);
        assertThat(ConfigChangeWords.summary(row))
                .isEqualTo("\"VAT payable\" is moved from account 2700 to 2701"
                        + " and its VAT rate, in percent set to 24.");
        assertThat(ConfigChangeWords.nextStep(row)).contains("approval");
    }

    @Test
    void aChangeThatMovesNothingSaysSo() {
        FinancialConfigChange row = new FinancialConfigChange();
        row.setPostingKey("ar");
        row.setCurrentCode("1200");
        row.setCurrentName("Accounts receivable");
        row.setProposedCode("1200");
        row.setProposedName("Accounts receivable");
        row.setState(FinancialConfigChange.DRAFT);
        assertThat(ConfigChangeWords.summary(row)).contains("moves nothing");
    }
}
