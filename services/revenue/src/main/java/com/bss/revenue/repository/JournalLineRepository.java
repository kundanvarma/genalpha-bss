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
}
