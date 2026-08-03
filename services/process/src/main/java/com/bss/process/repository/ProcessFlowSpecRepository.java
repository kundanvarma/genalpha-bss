package com.bss.process.repository;

import com.bss.process.entity.ProcessFlowSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessFlowSpecRepository extends JpaRepository<ProcessFlowSpec, ProcessFlowSpec.Key> {
    List<ProcessFlowSpec> findAllByTenantIdOrderByCodeAsc(String tenantId);
    Optional<ProcessFlowSpec> findByTenantIdAndCode(String tenantId, String code);
}
