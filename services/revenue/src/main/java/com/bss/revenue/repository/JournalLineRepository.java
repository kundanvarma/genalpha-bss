package com.bss.revenue.repository;

import com.bss.revenue.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findAllByTenantIdAndEntryIdOrderBySeqAsc(String tenantId, String entryId);

    List<JournalLine> findAllByTenantIdAndEntryIdInOrderBySeqAsc(String tenantId, List<String> entryIds);

    /** How many booked lines carry this account code — what makes an account "in use". */
    long countByTenantIdAndAccountCode(String tenantId, String accountCode);

    /**
     * How many booked lines carry EACH account code, in one question. The chart
     * of accounts needs this for every row at once, and thirty separate counts
     * from a browser is a burst the gateway would trim — which on screen looks
     * exactly like thirty unused accounts.
     * Returns rows of [accountCode, count].
     */
    @Query("SELECT l.accountCode, COUNT(l) FROM JournalLine l WHERE l.tenantId = :tenant "
            + "GROUP BY l.accountCode")
    List<Object[]> countByAccount(@Param("tenant") String tenant);

    /**
     * Per-account debit/credit totals over a date range — the governed input to
     * the reporting summary. Joined to the entry for the date; RLS still applies.
     * Returns rows of [accountCode, accountName, sumDebit, sumCredit].
     */
    @Query("SELECT l.accountCode, l.accountName, COALESCE(SUM(l.debit), 0), COALESCE(SUM(l.credit), 0) "
            + "FROM JournalLine l, JournalEntry e "
            + "WHERE l.entryId = e.id AND l.tenantId = :tenant AND e.entryDate BETWEEN :from AND :to "
            + "GROUP BY l.accountCode, l.accountName")
    List<Object[]> sumByAccountBetween(@Param("tenant") String tenant,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Per-month, per-customer net (credit − debit) for ONE account code — the
     * subscription-metrics engine's whole input: recurring revenue (4000) by
     * month by party, from the subledger's own rows (billed truth, no
     * downstream call). YEAR/MONTH are portable JPQL; RLS still applies.
     * Returns rows of [year, month, partyId, sumCredit, sumDebit].
     */
    @Query("SELECT YEAR(e.entryDate), MONTH(e.entryDate), e.partyId, "
            + "COALESCE(SUM(l.credit), 0), COALESCE(SUM(l.debit), 0) "
            + "FROM JournalLine l, JournalEntry e "
            + "WHERE l.entryId = e.id AND l.tenantId = :tenant AND l.accountCode = :code "
            + "AND e.entryDate BETWEEN :from AND :to "
            + "GROUP BY YEAR(e.entryDate), MONTH(e.entryDate), e.partyId")
    List<Object[]> monthlyNetByParty(@Param("tenant") String tenant, @Param("code") String code,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
