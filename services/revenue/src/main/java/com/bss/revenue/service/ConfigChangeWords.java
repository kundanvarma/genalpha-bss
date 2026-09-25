package com.bss.revenue.service;

import com.bss.revenue.entity.FinancialConfigChange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A proposed change, and the rung it waits on, said in one sentence.
 *
 * <p>The sentence is written here rather than in a console because three
 * channels would otherwise each invent their own words for the same change,
 * and because a channel that composes the sentence has to know what a posting
 * key means — which is business logic, and not a front end's to hold.
 */
public final class ConfigChangeWords {

    private ConfigChangeWords() {
    }

    /** What this change does, in the words a controller would use. */
    public static String summary(FinancialConfigChange row) {
        List<String> parts = new ArrayList<>();
        if (!same(row.getCurrentName(), row.getProposedName())) {
            parts.add("renamed to \"" + row.getProposedName() + "\"");
        }
        if (!same(row.getCurrentCode(), row.getProposedCode())) {
            parts.add("moved from account " + row.getCurrentCode() + " to " + row.getProposedCode());
        }
        if (moved(row.getCurrentValue(), row.getProposedValue())) {
            String setting = ChartWords.setting(row.getPostingKey());
            parts.add((setting == null ? "its setting" : "its " + setting)
                    + " set to " + say(row.getProposedValue()));
        }
        String what = "\"" + row.getCurrentName() + "\" is ";
        if (parts.isEmpty()) {
            return "\"" + row.getCurrentName() + "\" keeps everything it has — this change moves nothing.";
        }
        return what + join(parts) + ".";
    }

    /** What has to happen next for this change to reach the books. */
    public static String nextStep(FinancialConfigChange row) {
        return switch (String.valueOf(row.getState())) {
            case FinancialConfigChange.DRAFT -> "Validate it to see what it would do.";
            case FinancialConfigChange.VALIDATED -> "It needs an approval before it can be activated.";
            case FinancialConfigChange.APPROVED -> "Activate it when the general ledger is ready.";
            case FinancialConfigChange.ACTIVE -> "Live. Future postings follow it; booked lines keep their own.";
            case FinancialConfigChange.WITHDRAWN -> "Withdrawn. Nothing changed.";
            default -> "";
        };
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    /** A setting the way a person writes it: 25, not 25.000000; "nothing" when cleared. */
    private static String say(BigDecimal value) {
        return value == null ? "nothing" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean moved(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }
}
