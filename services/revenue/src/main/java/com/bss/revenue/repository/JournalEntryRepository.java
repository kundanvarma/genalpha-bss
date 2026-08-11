package com.bss.revenue.repository;

import com.bss.revenue.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
