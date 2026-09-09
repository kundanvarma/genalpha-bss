package com.bss.insight.repository;

import com.bss.insight.entity.DeskEvent;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeskEventRepository extends JpaRepository<DeskEvent, String> {
    List<DeskEvent> findByTenantIdAndOccurredAtAfterOrderByOccurredAtAsc(String tenantId, OffsetDateTime since);
}
