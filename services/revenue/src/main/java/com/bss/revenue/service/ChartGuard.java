package com.bss.revenue.service;

import com.bss.revenue.dto.ChangeFinding;
import com.bss.revenue.entity.AccountMapping;
import com.bss.revenue.repository.JournalLineRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What must be true of the chart of accounts before money is booked against it.
 *
 * <p>The same checks answer both doors: the ladder a human climbs on the
 * Configuration page, and the direct remap a seed or a machine caller uses. A
 * rule that only the screen enforced would not be a rule.
 *
 * <p>{@code blocks} means the change is refused — an account already carrying
 * postings cannot be left without a readable code or a name, and a rate that
 * moves money the wrong way is never a rate. {@code warns} means the change is
 * allowed but has a consequence an approver signs for: booked lines keep the
 * code they were born with, so renaming a used account leaves the ledger
 * holding both until the general ledger is told.
 */
@Component
public class ChartGuard {

    /** What a general ledger can read back: no spaces, no commas, no quotes. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JournalLineRepository lines;

    public ChartGuard(JournalLineRepository lines) {
        this.lines = lines;
    }

    /** How many booked lines already carry this account code. */
    public long postingsUsing(String tenantId, String accountCode) {
        return accountCode == null || accountCode.isBlank()
                ? 0L : lines.countByTenantIdAndAccountCode(tenantId, accountCode);
    }

    /**
     * Everything wrong (and everything consequential) about moving {@code key}
     * from {@code live} to the proposed code, name and setting.
     */
    public List<ChangeFinding> check(String key, AccountMapping live, String code, String name,
            BigDecimal value, long postingsUsing) {
        List<ChangeFinding> found = new ArrayList<>();
        String heldBy = postingsUsing > 0
                ? " " + count(postingsUsing) + " booked "
                + (postingsUsing == 1 ? "line already carries" : "lines already carry")
                + " account " + live.getAccountCode() + "."
                : "";
        if (code == null || code.isBlank()) {
            found.add(ChangeFinding.blocks("An account needs a code — it is what the general ledger reads."
                    + heldBy));
        } else if (!CODE.matcher(code).matches()) {
            found.add(ChangeFinding.blocks("\"" + code + "\" is not an account code a ledger can read:"
                    + " letters, digits, dot, dash and underscore only, up to 32 of them." + heldBy));
        }
        if (name == null || name.isBlank()) {
            found.add(ChangeFinding.blocks("An account with no name cannot be read on a statement." + heldBy));
        }
        if (value != null && value.signum() < 0) {
            found.add(ChangeFinding.blocks("A negative setting would move money the wrong way."));
        }
        if ("tax".equals(key) && value != null && value.compareTo(HUNDRED) > 0) {
            found.add(ChangeFinding.blocks("A VAT rate above 100% is not a rate."));
        }
        if (postingsUsing > 0 && code != null && !code.isBlank()
                && !code.equals(live.getAccountCode())) {
            found.add(ChangeFinding.warns(count(postingsUsing) + " booked "
                    + (postingsUsing == 1 ? "line keeps" : "lines keep") + " account "
                    + live.getAccountCode() + ", because a posting keeps the code it was born with."
                    + " The ledger will hold both codes until the general ledger is told about "
                    + code + "."));
        }
        if (postingsUsing > 0 && changed(value, live.getConfigValue()) && ChartWords.setting(key) != null) {
            found.add(ChangeFinding.warns("The " + ChartWords.setting(key)
                    + " changes for FUTURE postings only; everything already booked keeps what it was billed at."));
        }
        return found;
    }

    /** Has anything on the live row moved since this proposal was written down? */
    public boolean movedUnder(AccountMapping live, String code, String name, BigDecimal value) {
        return !equalText(live.getAccountCode(), code)
                || !equalText(live.getAccountName(), name)
                || changed(live.getConfigValue(), value);
    }

    public static boolean blocked(List<ChangeFinding> found) {
        return found.stream().anyMatch(ChangeFinding::blocking);
    }

    private static boolean equalText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean changed(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }

    /** 3 815, never 3815 — a controller reads counts, not digit strings. */
    private static String count(long n) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(n);
    }
}
