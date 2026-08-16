package com.bss.quote.repository;

import com.bss.quote.entity.PipelineSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PipelineSnapshotRepository extends JpaRepository<PipelineSnapshot, String> {
    List<PipelineSnapshot> findTop52ByTenantIdOrderByCapturedAtDesc(String tenantId);
}
