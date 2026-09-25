package com.bss.revenue.dto;

import java.time.LocalDate;

/**
 * What a controller asked the journal for: a date range, one kind of business
 * event, one account, and a page of the answer. Every field may be null, and
 * null means "everything" — so the same record describes the unfiltered book.
 *
 * <p>It is a record and not a map of query parameters because the export and
 * the list must be able to answer the SAME question: a reconciliation that
 * downloads something other than what it looked at is worse than no export.
 */
public record JournalFilter(
        LocalDate from,
        LocalDate to,
        String sourceType,
        String account,
        int limit,
        int offset) {

    /** The whole book, one page at a time. */
    public static JournalFilter page(int limit, int offset) {
        return new JournalFilter(null, null, null, null, limit, offset);
    }

    /** One day, as the older ?date= callers asked for it. */
    public static JournalFilter onDay(LocalDate date, int limit) {
        return new JournalFilter(date, date, null, null, limit, 0);
    }

    public JournalFilter withPage(int newLimit, int newOffset) {
        return new JournalFilter(from, to, sourceType, account, newLimit, newOffset);
    }

    /* An absent end of the range becomes a BOUND rather than a null. A null
     * date parameter has no type for PostgreSQL to infer, and the query dies
     * with "could not determine data type of parameter" — on the export, which
     * is exactly where nobody would look. */
    private static final LocalDate DAWN = LocalDate.of(1900, 1, 1);
    private static final LocalDate DUSK = LocalDate.of(9999, 12, 31);

    public LocalDate fromBound() {
        return from == null ? DAWN : from;
    }

    public LocalDate toBound() {
        return to == null ? DUSK : to;
    }
}
