package com.bss.revenue.repository;

import com.bss.revenue.entity.JournalEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

    Optional<JournalEntry> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantIdAndSourceRef(String tenantId, String sourceRef);

    List<JournalEntry> findAllByTenantIdAndEntryDateOrderByCreatedAtAsc(String tenantId, LocalDate entryDate);

    List<JournalEntry> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<JournalEntry> findByTenantIdAndSourceRef(String tenantId, String sourceRef);

    long countByTenantIdAndSourceTypeAndEntryDateBetween(
            String tenantId, String sourceType, LocalDate from, LocalDate to);

    /*
     * The journal a controller actually reconciles with: a date RANGE, one kind
     * of business event, one account — and a page of it, newest first. Every
     * filter is optional and a null means "everything", so one query answers the
     * whole filter bar instead of a method per combination.
     *
     * The account filter is an EXISTS over the lines rather than a join, so an
     * entry appears once however many of its lines carry the account. RLS still
     * applies underneath.
     */
    // The dates are BOUNDS, never nulls. "(:from IS NULL OR …)" reads well and
    // dies on PostgreSQL with "could not determine data type of parameter" the
    // moment one end of the range is absent, because a null date parameter has
    // no type to infer. The service widens an absent end to the bound below,
    // so the query always compares two real dates — and H2 agrees.
    String FILTERED = "FROM JournalEntry e WHERE e.tenantId = :tenant "
            + "AND e.entryDate >= :from AND e.entryDate <= :to "
            + "AND (:sourceType IS NULL OR e.sourceType = :sourceType) "
            + "AND (:account IS NULL OR EXISTS (SELECT 1 FROM JournalLine l "
            + "     WHERE l.entryId = e.id AND l.tenantId = e.tenantId AND l.accountCode = :account))";

    @Query("SELECT e " + FILTERED + " ORDER BY e.entryDate DESC, e.createdAt DESC")
    List<JournalEntry> filtered(@Param("tenant") String tenant, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("sourceType") String sourceType,
            @Param("account") String account, Pageable page);

    @Query("SELECT COUNT(e) " + FILTERED)
    long countFiltered(@Param("tenant") String tenant, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("sourceType") String sourceType,
            @Param("account") String account);

    /** The kinds of business event this tenant's book actually holds. */
    @Query("SELECT DISTINCT e.sourceType FROM JournalEntry e WHERE e.tenantId = :tenant ORDER BY e.sourceType")
    List<String> sourceTypes(@Param("tenant") String tenant);
}
