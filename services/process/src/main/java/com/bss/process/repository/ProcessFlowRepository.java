package com.bss.process.repository;

import com.bss.process.entity.ProcessFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessFlowRepository extends JpaRepository<ProcessFlow, String> {
    Optional<ProcessFlow> findByIdAndTenantId(String id, String tenantId);
    Optional<ProcessFlow> findByTenantIdAndCorrelationId(String tenantId, String correlationId);
    List<ProcessFlow> findTop100ByTenantIdOrderByStartedAtDesc(String tenantId);
    List<ProcessFlow> findByTenantIdAndStateOrderByStartedAtDesc(String tenantId, String state);
    List<ProcessFlow> findByStateOrderByStartedAtAsc(String state);
}
