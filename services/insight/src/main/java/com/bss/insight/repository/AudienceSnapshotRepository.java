package com.bss.insight.repository;

import com.bss.insight.entity.AudienceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AudienceSnapshotRepository extends JpaRepository<AudienceSnapshot, String> {

    List<AudienceSnapshot> findByTenantIdAndAudienceId(String tenantId, String audienceId);

    void deleteByTenantIdAndAudienceId(String tenantId, String audienceId);
}
