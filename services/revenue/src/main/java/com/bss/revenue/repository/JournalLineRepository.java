package com.bss.revenue.repository;

import com.bss.revenue.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findAllByTenantIdAndEntryIdOrderBySeqAsc(String tenantId, String entryId);

    List<JournalLine> findAllByTenantIdAndEntryIdInOrderBySeqAsc(String tenantId, List<String> entryIds);
}
