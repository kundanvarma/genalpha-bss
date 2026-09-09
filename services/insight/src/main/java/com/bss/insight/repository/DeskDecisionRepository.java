package com.bss.insight.repository;

import com.bss.insight.entity.DeskDecision;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeskDecisionRepository extends JpaRepository<DeskDecision, String> {
    List<DeskDecision> findByTenantIdAndDecidedAtAfter(String tenantId, OffsetDateTime since);
}
